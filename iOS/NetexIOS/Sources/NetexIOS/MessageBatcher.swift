import Foundation

final class MessageBatcher<Value> {
    private let maxBatchSize: Int
    private let delay: TimeInterval
    private let queue: DispatchQueue
    private let onFlush: ([Value]) -> Void
    private var pending: [Value] = []
    private var scheduled = false

    init(maxBatchSize: Int, delay: TimeInterval, queue: DispatchQueue = .main, onFlush: @escaping ([Value]) -> Void) {
        self.maxBatchSize = max(1, maxBatchSize)
        self.delay = delay
        self.queue = queue
        self.onFlush = onFlush
    }

    func append(_ value: Value) {
        pending.append(value)
        if pending.count >= maxBatchSize {
            flush()
            return
        }
        schedule()
    }

    func flush() {
        guard !pending.isEmpty else { return }
        let batch = pending
        pending.removeAll()
        scheduled = false
        onFlush(batch)
    }

    private func schedule() {
        guard !scheduled else { return }
        scheduled = true
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.flush()
        }
    }
}
