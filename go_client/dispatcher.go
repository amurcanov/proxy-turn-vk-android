package main

import (
	"context"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	returnChBuf = 384

	// chunkSize — количество последовательных пакетов, отправляемых в один worker
	// перед переключением на следующий.
	//
	// Зачем: при round-robin (chunk=1) каждый пакет летит через разный TURN relay
	// с разным latency, что приводит к reorder на сервере. TCP внутри WireGuard
	// интерпретирует reorder как потери → cwnd collapse → скорость single-flow
	// падает до ~8 KB/s.
	//
	// С chunk=8: пакеты в пределах одного TCP congestion window (~10 пакетов при
	// initial cwnd) уходят через один TURN relay → прилетают по порядку.
	// Reorder возможен только между chunk-границами, что покрывается WG replay
	// window (2048 пакетов).
	//
	// Агрегатная пропускная способность не меняется — все workers загружены
	// равномерно по-прежнему (каждый получает 1/N от общего трафика за время).
	chunkSize = 8
)

type WorkerSlot struct {
	ID     int
	SendCh chan []byte
}

type Dispatcher struct {
	localConn  net.PacketConn
	clientAddr atomic.Pointer[net.Addr]
	mu         sync.Mutex
	workers    []*WorkerSlot
	rrIndex    int
	rrCount    int // сколько пакетов отправлено в текущий worker (0..chunkSize-1)
	ReturnCh   chan []byte
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	stats      *Stats
}

func NewDispatcher(ctx context.Context, localConn net.PacketConn, stats *Stats) *Dispatcher {
	dctx, dcancel := context.WithCancel(ctx)
	d := &Dispatcher{
		localConn: localConn,
		ReturnCh:  make(chan []byte, returnChBuf),
		ctx:       dctx,
		cancel:    dcancel,
		stats:     stats,
	}

	d.wg.Add(2)
	go d.readLoop()
	go d.writeLoop()
	return d
}

func (d *Dispatcher) Shutdown() {
	d.cancel()
	d.wg.Wait()
}

func (d *Dispatcher) Register(w *WorkerSlot) {
	d.mu.Lock()
	d.workers = append(d.workers, w)
	count := len(d.workers)
	d.mu.Unlock()
	log.Printf("[ДИСП] Воркер #%d зарегистрирован (всего: %d)", w.ID, count)
}

func (d *Dispatcher) Unregister(slot *WorkerSlot) {
	d.mu.Lock()
	for i, w := range d.workers {
		if w == slot {
			d.workers = append(d.workers[:i], d.workers[i+1:]...)
			break
		}
	}
	remaining := len(d.workers)
	// Подстраховка: если текущий rrIndex вылез за границу после удаления
	if d.rrIndex >= remaining && remaining > 0 {
		d.rrIndex = d.rrIndex % remaining
	}
	d.rrCount = 0
	d.mu.Unlock()
	log.Printf("[ДИСП] Воркер #%d отключён (осталось: %d)", slot.ID, remaining)
}

// readLoop читает WireGuard-пакеты и распределяет по workers chunk'ами.
//
// Логика: отправляем chunkSize подряд пакетов в один worker, потом переходим
// к следующему. Если текущий worker перегружен (канал полный) — немедленно
// ищем свободный worker и начинаем новый chunk на нём. Это гарантирует:
//   - В рамках chunk пакеты идут через один TURN relay → in-order delivery
//   - Между chunks — разные relay → максимальная агрегатная скорость
//   - Нет блокировки, нет буферизации, нет дополнительного latency
func (d *Dispatcher) readLoop() {
	defer d.wg.Done()

	buf := make([]byte, readBufSize)
	for {
		if err := d.ctx.Err(); err != nil {
			return
		}

		n, addr, err := d.localConn.ReadFrom(buf)
		if err != nil {
			if d.ctx.Err() != nil {
				return
			}
			time.Sleep(10 * time.Millisecond)
			continue
		}

		d.clientAddr.Store(&addr)
		atomic.AddInt64(&d.stats.TotalBytesUp, int64(n))

		pkt := make([]byte, n)
		copy(pkt, buf[:n])

		d.mu.Lock()
		nw := len(d.workers)
		if nw == 0 {
			d.mu.Unlock()
			continue
		}

		sent := false
		idx := d.rrIndex % nw

		// Пробуем текущий worker (chunk affinity)
		w := d.workers[idx]
		select {
		case w.SendCh <- pkt:
			sent = true
			d.rrCount++
			if d.rrCount >= chunkSize {
				d.rrIndex = (idx + 1) % nw
				d.rrCount = 0
			}
		default:
			// Текущий worker перегружен — ищем свободный, начинаем новый chunk
			for i := 1; i < nw; i++ {
				altIdx := (idx + i) % nw
				select {
				case d.workers[altIdx].SendCh <- pkt:
					sent = true
					d.rrIndex = altIdx
					d.rrCount = 1 // первый пакет нового chunk'а уже отправлен
				default:
				}
				if sent {
					break
				}
			}
		}

		if !sent {
			// Все workers перегружены — сдвигаем указатель, пакет дропается
			d.rrIndex = (idx + 1) % nw
			d.rrCount = 0
		}
		d.mu.Unlock()
	}
}

func (d *Dispatcher) writeLoop() {
	defer d.wg.Done()

	for {
		select {
		case <-d.ctx.Done():
			return
		case pkt := <-d.ReturnCh:
			addrPtr := d.clientAddr.Load()
			if addrPtr == nil {
				continue
			}
			addr := *addrPtr
			if _, err := d.localConn.WriteTo(pkt, addr); err != nil {
				if d.ctx.Err() != nil {
					return
				}
			}
			atomic.AddInt64(&d.stats.TotalBytesDown, int64(len(pkt)))
		}
	}
}
