package jp.metamaps.mapview

import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsUserAction

internal data class MapLoadFailure(
    val error: MetamapsError,
    val retryable: Boolean,
)

internal fun classifyHttpLoadFailure(statusCode: Int): MapLoadFailure = when (statusCode) {
    404 -> mapLoadFailure(
        MetamapsError.Code.MAP_NOT_FOUND,
        recoverable = false,
        userAction = MetamapsUserAction.CHECK_CONFIGURATION,
        retryable = false,
        detail = "HTTP 404",
    )
    401, 403 -> mapLoadFailure(
        MetamapsError.Code.MAP_ACCESS_DENIED,
        recoverable = true,
        userAction = MetamapsUserAction.RETRY,
        retryable = false,
        detail = "HTTP $statusCode",
    )
    408, 429 -> retryableWebLoadFailure("HTTP $statusCode")
    in 500..599 -> retryableWebLoadFailure("HTTP $statusCode")
    else -> mapLoadFailure(
        MetamapsError.Code.WEB_CONTENT_LOAD_FAILED,
        recoverable = true,
        userAction = MetamapsUserAction.RETRY,
        retryable = false,
        detail = "HTTP $statusCode",
    )
}

internal fun retryableWebLoadFailure(detail: String): MapLoadFailure = mapLoadFailure(
    MetamapsError.Code.WEB_CONTENT_LOAD_FAILED,
    recoverable = true,
    userAction = MetamapsUserAction.RETRY,
    retryable = true,
    detail = detail,
)

internal fun sslLoadFailure(detail: String): MapLoadFailure = mapLoadFailure(
    MetamapsError.Code.WEB_CONTENT_LOAD_FAILED,
    recoverable = false,
    userAction = MetamapsUserAction.CHECK_CONFIGURATION,
    retryable = false,
    detail = detail,
)

internal fun nonRetryableWebLoadFailure(detail: String): MapLoadFailure = mapLoadFailure(
    MetamapsError.Code.WEB_CONTENT_LOAD_FAILED,
    recoverable = true,
    userAction = MetamapsUserAction.RETRY,
    retryable = false,
    detail = detail,
)

internal fun mapLoadRetryDelayMillis(failureIndex: Int): Long = when (failureIndex) {
    0 -> 1_000L
    1 -> 2_000L
    else -> 4_000L
}

internal fun shouldInitialDocumentRequestTimeout(responseObserved: Boolean): Boolean =
    !responseObserved

private fun mapLoadFailure(
    code: MetamapsError.Code,
    recoverable: Boolean,
    userAction: MetamapsUserAction,
    retryable: Boolean,
    detail: String,
) = MapLoadFailure(
    MetamapsError(
        code = code,
        message = "The embedded Metamaps map could not be loaded.",
        recoverable = recoverable,
        userAction = userAction,
        debugDetail = detail,
    ),
    retryable,
)
