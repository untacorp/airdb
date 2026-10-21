import Foundation

extension NSLock {
    /// Executes a closure while holding the lock in an async-safe scoped manner.
    @discardableResult
    @inlinable
    public func withScopedLock<T>(_ body: () throws -> T) rethrows -> T {
        self.lock()
        defer { self.unlock() }
        return try body()
    }
}
