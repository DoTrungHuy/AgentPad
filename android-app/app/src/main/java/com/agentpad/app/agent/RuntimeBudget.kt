package com.agentpad.app.agent

/**
 * Simple per-turn / per-work-session budgets for provider calls and wall time.
 */
class RuntimeBudget(
    private val maxProviderCalls: Int = DEFAULT_MAX_PROVIDER_CALLS,
    private val maxWallTimeMillis: Long = DEFAULT_MAX_WALL_TIME_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var providerCalls = 0
    private var startedAt: Long? = null

    fun start(now: Long = nowMillis()) {
        startedAt = now
        providerCalls = 0
    }

    fun reset() {
        providerCalls = 0
        startedAt = null
    }

    fun consumeProviderCall() {
        checkNotExpired()
        check(providerCalls < maxProviderCalls) {
            "本回合模型调用次数已达上限（$maxProviderCalls 次）"
        }
        providerCalls += 1
    }

    fun isExpired(now: Long = nowMillis()): Boolean {
        val start = startedAt ?: return false
        return now - start > maxWallTimeMillis
    }

    fun checkNotExpired(now: Long = nowMillis()) {
        check(!isExpired(now)) {
            "本回合执行时间已超过 ${maxWallTimeMillis / 1000} 秒上限"
        }
    }

    companion object {
        const val DEFAULT_MAX_PROVIDER_CALLS = 3
        const val DEFAULT_MAX_WALL_TIME_MILLIS = 5 * 60 * 1000L
    }
}
