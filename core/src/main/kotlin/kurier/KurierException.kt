package kurier

/**
 * The one failure type portable bot code needs to catch: adapters surface platform failures from
 * [Channel.send], [Channel.sendStreaming], [SentMessage.edit], and [SentMessage.delete] as
 * [KurierException] (or a subclass). Cancellation is exempt — `CancellationException` always passes
 * through untouched so structured concurrency keeps working.
 *
 * [retryable] is the cross-platform recovery signal: `true` when trying again can succeed (rate
 * limited, transient network or server failure), `false` when it can't (unknown channel, message
 * too long, revoked token). [cause] preserves the underlying platform exception — the failure-path
 * counterpart of [IncomingMessage.raw]: platform-aware code may inspect it, and it keeps stack
 * traces complete.
 *
 * Open, not sealed, so adapters outside this repo can subclass it and richer subtypes can land in
 * minor releases — `catch (e: KurierException)` keeps matching either way.
 */
public open class KurierException(
    message: String,
    cause: Throwable? = null,
    public val retryable: Boolean = false,
) : RuntimeException(message, cause)
