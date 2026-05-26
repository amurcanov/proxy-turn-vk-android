// SPDX-License-Identifier: MIT
// obfs.go — WebRTC SRTP-like obfuscation for DTLS traffic
// Each UDP packet is wrapped in an RTP header making it indistinguishable
// from a real WebRTC OPUS audio stream to DPI systems.
//
// Packet format:
//   [RTP Header 12 bytes][ChaCha20-Poly1305 payload+tag][Padding 0-N bytes][PadLen 1 byte]
//
// The RTP header fields (SSRC + SeqNum + Timestamp) form the 12-byte AEAD nonce,
// so no separate nonce prefix is needed.

package main

import (
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"
)

var aeadCache sync.Map

func getAEAD(key []byte) (cipher.AEAD, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes", wrapKeyLen)
	}
	keyStr := string(key)
	if val, ok := aeadCache.Load(keyStr); ok {
		return val.(cipher.AEAD), nil
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	aeadCache.Store(keyStr, aead)
	return aead, nil
}

// ─── Configuration ───

// ObfsConfig holds per-session obfuscation parameters.
type ObfsConfig struct {
	SSRC        uint32 // Synchronization Source — random per session
	PayloadType uint8  // RTP payload type (111 = OPUS dynamic)
	PaddingMax  int    // Max random padding bytes appended
}

// NewObfsConfig creates a config with random SSRC and sane defaults.
func NewObfsConfig() *ObfsConfig {
	var buf [4]byte
	rand.Read(buf[:])
	return &ObfsConfig{
		SSRC:        binary.BigEndian.Uint32(buf[:]),
		PayloadType: 111, // dynamic PT for OPUS
		PaddingMax:  24,
	}
}

// ─── Per-direction state (sequence + timestamp counters) ───

// ObfsState tracks monotonically increasing RTP sequence number and timestamp using a 48-bit packet counter.
type ObfsState struct {
	mu      sync.Mutex
	initSeq uint16
	initTs  uint32
	count   uint64
}

// NewObfsState creates a state with random initial seq/ts and count=0.
func NewObfsState() *ObfsState {
	var buf [6]byte
	rand.Read(buf[:])
	return &ObfsState{
		initSeq: binary.BigEndian.Uint16(buf[0:2]),
		initTs:  binary.BigEndian.Uint32(buf[2:6]),
		count:   0,
	}
}

// ─── Nonce derivation ───

// obfsBuildNonce deterministically builds a 12-byte AEAD nonce from RTP fields.
//
//	[SSRC 4B][SeqNum 2B][0x00 0x00][Timestamp 4B]
func obfsBuildNonce(ssrc uint32, seq uint16, ts uint32) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint32(n[0:4], ssrc)
	binary.BigEndian.PutUint16(n[4:6], seq)
	// n[6], n[7] = 0x00 — zero padding for unique nonce space
	binary.BigEndian.PutUint32(n[8:12], ts)
	return n
}

// ─── Wrap (encrypt + add RTP header) ───

// obfsWrapPacket wraps a plaintext payload into an RTP-like packet with authenticated encryption.
// The output looks like:
//
//	[V=2,P=1,X=0,CC=0 | PT | SeqNum | Timestamp | SSRC | encrypted_payload | padding | padLen]
func obfsWrapPacket(key, payload []byte, cfg *ObfsConfig, state *ObfsState) ([]byte, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(payload) == 0 {
		return nil, errors.New("obfs: empty payload")
	}

	state.mu.Lock()
	c := state.count
	state.count++
	state.mu.Unlock()

	seq := state.initSeq + uint16(c)
	ts := state.initTs + uint32(c)*960 + uint32(c>>16)

	// Build nonce from RTP fields
	nonce := obfsBuildNonce(cfg.SSRC, seq, ts)

	// Determine padding
	padRand := 0
	if cfg.PaddingMax > 0 {
		var rndBuf [1]byte
		rand.Read(rndBuf[:])
		padRand = int(rndBuf[0]) % cfg.PaddingMax
	}
	padTotal := padRand + 1 // +1 for the length byte itself

	// Allocate output: 12 (header) + payload + AEAD tag + padTotal
	outLen := 12 + len(payload) + chacha20poly1305.Overhead + padTotal
	out := make([]byte, outLen)

	// RTP Header (12 bytes)
	out[0] = 0x80 | 0x20 // V=2, P=1 (padding present)
	out[1] = cfg.PayloadType & 0x7F
	binary.BigEndian.PutUint16(out[2:4], seq)
	binary.BigEndian.PutUint32(out[4:8], ts)
	binary.BigEndian.PutUint32(out[8:12], cfg.SSRC)

	aead, err := getAEAD(key)
	if err != nil {
		return nil, fmt.Errorf("obfs: cipher init: %w", err)
	}
	sealed := aead.Seal(out[12:12], nonce, payload, out[:12])

	// Random padding bytes
	padStart := 12 + len(sealed)
	if padRand > 0 {
		rand.Read(out[padStart : padStart+padRand])
	}

	// Last byte = total padding count (RFC 3550 §5.1)
	out[outLen-1] = byte(padTotal)

	return out, nil
}

// ─── Unwrap (strip RTP header + decrypt) ───

// obfsUnwrapPacket strips the RTP header, removes padding, and decrypts the payload.
// Returns number of plaintext bytes written to dst.
func obfsUnwrapPacket(key, wire, dst []byte) (int, error) {
	if len(key) != wrapKeyLen {
		return 0, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(wire) < 13 { // 12 header + at least 1 byte
		return 0, errors.New("obfs: packet too short")
	}

	// Validate RTP version
	if (wire[0] >> 6) != 2 {
		return 0, errors.New("obfs: not RTP v2")
	}

	// Extract RTP fields for nonce
	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	ssrc := binary.BigEndian.Uint32(wire[8:12])

	// Handle padding (P bit)
	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 {
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > payloadEnd-12 {
			return 0, fmt.Errorf("obfs: invalid padding length %d", padLen)
		}
		payloadEnd -= padLen
	}

	ciphertextLen := payloadEnd - 12
	if ciphertextLen <= chacha20poly1305.Overhead {
		return 0, errors.New("obfs: no payload after stripping header/padding")
	}
	if ciphertextLen-chacha20poly1305.Overhead > len(dst) {
		return 0, errors.New("obfs: dst buffer too small")
	}

	// Build nonce and decrypt
	nonce := obfsBuildNonce(ssrc, seq, ts)
	aead, err := getAEAD(key)
	if err != nil {
		return 0, fmt.Errorf("obfs: cipher init: %w", err)
	}
	plain, err := aead.Open(dst[:0], nonce, wire[12:payloadEnd], wire[:12])
	if err != nil {
		return 0, fmt.Errorf("obfs: auth: %w", err)
	}

	return len(plain), nil
}

// ─── Detection ───

// obfsIsRTPPacket checks if a raw UDP packet looks like our obfuscated RTP.
// Used by the server and client to reject non-obfuscated packets.
func obfsIsRTPPacket(wire []byte) bool {
	if len(wire) < 13 {
		return false
	}
	// RTP version must be 2
	if (wire[0] >> 6) != 2 {
		return false
	}
	// Our payload type = 111
	pt := wire[1] & 0x7F
	return pt == 111
}
