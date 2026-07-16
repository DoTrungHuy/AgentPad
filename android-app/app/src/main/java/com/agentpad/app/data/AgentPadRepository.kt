package com.agentpad.app.data

import com.agentpad.app.data.local.AgentThreadEntity
import com.agentpad.app.data.local.AgentTurnEntity
import com.agentpad.app.data.local.AuditDao
import com.agentpad.app.data.local.AuditEventEntity
import com.agentpad.app.data.local.ThreadAttachmentEntity
import com.agentpad.app.data.local.ThreadDao
import com.agentpad.app.data.local.ThreadMessageEntity
import com.agentpad.app.domain.AgentThread
import com.agentpad.app.domain.AgentTurn
import com.agentpad.app.domain.MessageKind
import com.agentpad.app.domain.MessageRole
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.domain.ThreadAttachment
import com.agentpad.app.domain.ThreadMessage
import com.agentpad.app.domain.ThreadSnapshot
import com.agentpad.app.domain.ThreadStatus
import com.agentpad.app.domain.TurnStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AgentPadRepository(
    private val threadDao: ThreadDao,
    private val auditDao: AuditDao
) {
    fun observeThreads(): Flow<List<AgentThread>> = threadDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun interruptActiveTurns() {
        threadDao.interruptActiveTurns(System.currentTimeMillis())
    }

    suspend fun loadThread(threadId: String): ThreadSnapshot? {
        val thread = threadDao.getThread(threadId) ?: return null
        return ThreadSnapshot(
            thread = thread.toDomain(),
            turns = threadDao.getTurns(threadId).map { it.toDomain() },
            messages = threadDao.getMessages(threadId).map { it.toDomain() },
            attachments = threadDao.getAttachments(threadId).map { it.toDomain() }
        )
    }

    suspend fun beginTurn(
        threadId: String?,
        goal: String,
        attachment: ThreadAttachment?
    ): AgentTurn {
        val now = System.currentTimeMillis()
        val resolvedThreadId = threadId ?: UUID.randomUUID().toString()
        val newThread = if (threadId == null) {
            AgentThreadEntity(
                id = resolvedThreadId,
                title = goal.lineSequence().firstOrNull().orEmpty().take(80).ifBlank { "新任务" },
                status = ThreadStatus.ACTIVE.name,
                createdAt = now,
                updatedAt = now
            )
        } else {
            null
        }
        val turn = AgentTurn(
            threadId = resolvedThreadId,
            ordinal = 0,
            goal = goal,
            plan = null,
            status = TurnStatus.PLANNING,
            result = null,
            createdAt = now,
            updatedAt = now
        )
        val stored = threadDao.insertTurnBundle(
            newThread = newThread,
            turn = turn.toEntity(title = goal.take(80)),
            message = ThreadMessage(
                threadId = resolvedThreadId,
                turnId = turn.id,
                role = MessageRole.USER,
                kind = MessageKind.GOAL,
                content = goal,
                createdAt = now
            ).toEntity(),
            attachment = attachment
                ?.copy(threadId = resolvedThreadId, turnId = turn.id)
                ?.toEntity(),
            now = now
        )
        return stored.toDomain()
    }

    suspend fun savePlan(turn: AgentTurn, plan: TaskPlan): AgentTurn {
        val now = System.currentTimeMillis()
        val updated = turn.copy(
            plan = plan,
            status = TurnStatus.AWAITING_APPROVAL,
            updatedAt = now
        )
        threadDao.upsertTurn(updated.toEntity(plan.title))
        threadDao.insertMessage(
            ThreadMessage(
                threadId = turn.threadId,
                turnId = turn.id,
                role = MessageRole.ASSISTANT,
                kind = MessageKind.PLAN,
                content = plan.summary,
                createdAt = now
            ).toEntity()
        )
        threadDao.touchThread(turn.threadId, ThreadStatus.ACTIVE.name, now)
        audit(turn.id, null, "PLAN_CREATED", "任务计划已生成，共 ${plan.actions.size} 步")
        return updated
    }

    suspend fun updateStatus(
        turn: AgentTurn,
        status: TurnStatus,
        result: String? = null
    ): AgentTurn {
        val now = System.currentTimeMillis()
        val existing = threadDao.getTurn(turn.id)
        val currentStatus = existing?.status?.let {
            runCatching { TurnStatus.valueOf(it) }.getOrNull()
        }
        // Terminal states are sticky, except CANCELLED may override COMPLETED/FAILED
        // so user cancel wins a race against a late success/failure write.
        if (existing != null && currentStatus in TERMINAL_STATUSES && currentStatus != status) {
            val cancelOverrides =
                status == TurnStatus.CANCELLED &&
                    currentStatus in setOf(TurnStatus.COMPLETED, TurnStatus.FAILED)
            if (!cancelOverrides) {
                return existing.toDomain()
            }
        }
        val updated = turn.copy(
            status = status,
            result = result ?: turn.result,
            updatedAt = now
        )
        threadDao.upsertTurn(updated.toEntity(turn.plan?.title ?: turn.goal.take(80)))
        if (!result.isNullOrBlank()) {
            threadDao.insertMessage(
                ThreadMessage(
                    threadId = turn.threadId,
                    turnId = turn.id,
                    role = MessageRole.ASSISTANT,
                    kind = MessageKind.RESULT,
                    content = result,
                    createdAt = now
                ).toEntity()
            )
        }
        val threadStatus = when (status) {
            TurnStatus.COMPLETED -> ThreadStatus.COMPLETED
            TurnStatus.FAILED -> ThreadStatus.FAILED
            else -> ThreadStatus.ACTIVE
        }
        threadDao.touchThread(turn.threadId, threadStatus.name, now)
        audit(turn.id, null, "STATUS_CHANGED", "任务状态变更为 ${status.name}")
        return updated
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(
            TurnStatus.COMPLETED,
            TurnStatus.FAILED,
            TurnStatus.CANCELLED,
            TurnStatus.SUPERSEDED
        )
    }

    suspend fun addContextSummary(threadId: String, summary: String) {
        val now = System.currentTimeMillis()
        threadDao.insertMessage(
            ThreadMessage(
                threadId = threadId,
                turnId = null,
                role = MessageRole.SYSTEM,
                kind = MessageKind.CONTEXT_SUMMARY,
                content = summary,
                createdAt = now
            ).toEntity()
        )
        threadDao.touchThread(threadId, ThreadStatus.ACTIVE.name, now)
    }

    suspend fun deleteThread(threadId: String): List<ThreadAttachment> {
        val attachments = threadDao.getAttachments(threadId).map { it.toDomain() }
        auditDao.deleteForThread(threadId)
        threadDao.deleteThread(threadId)
        return attachments
            .distinctBy { it.uri }
            .filter { threadDao.countAttachmentsByUri(it.uri) == 0 }
    }

    suspend fun recentAuditSummaries(limit: Int = 20): List<String> =
        auditDao.getRecent(limit).map { "${it.eventType}: ${it.summary}" }

    suspend fun audit(
        taskId: String,
        actionId: String?,
        eventType: String,
        summary: String
    ) {
        auditDao.insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                actionId = actionId,
                eventType = eventType,
                summary = summary.take(500),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun AgentThreadEntity.toDomain() = AgentThread(
        id = id,
        title = title,
        status = ThreadStatus.valueOf(status),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun AgentTurnEntity.toDomain() = AgentTurn(
        id = id,
        threadId = threadId,
        ordinal = ordinal,
        goal = goal,
        plan = PlanCodec.decode(planJson),
        status = TurnStatus.valueOf(status),
        result = result,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ThreadMessageEntity.toDomain() = ThreadMessage(
        id = id,
        threadId = threadId,
        turnId = turnId,
        role = MessageRole.valueOf(role),
        kind = MessageKind.valueOf(kind),
        content = content,
        createdAt = createdAt
    )

    private fun ThreadAttachmentEntity.toDomain() = ThreadAttachment(
        id = id,
        threadId = threadId,
        turnId = turnId,
        uri = uri,
        name = name,
        mimeType = mimeType,
        size = size,
        createdAt = createdAt
    )

    private fun AgentTurn.toEntity(title: String) = AgentTurnEntity(
        id = id,
        threadId = threadId,
        ordinal = ordinal,
        title = title,
        goal = goal,
        status = status.name,
        planJson = plan?.let(PlanCodec::encode).orEmpty(),
        result = result,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ThreadMessage.toEntity() = ThreadMessageEntity(
        id = id,
        threadId = threadId,
        turnId = turnId,
        role = role.name,
        kind = kind.name,
        content = content,
        createdAt = createdAt
    )

    private fun ThreadAttachment.toEntity() = ThreadAttachmentEntity(
        id = id,
        threadId = threadId,
        turnId = turnId,
        uri = uri,
        name = name,
        mimeType = mimeType,
        size = size,
        createdAt = createdAt
    )
}
