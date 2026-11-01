package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.groups.P2PGroupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages archived 1-on-1 conversations and P2P groups, plus bulk cleanup (archive or delete)
 * of older inactive chats.
 */
object ChatArchiveManager {
    const val PREFS_NAME = "nemix_chat_archive_prefs"
    private const val KEY_ARCHIVED_IDS = "archived_conversation_ids"

    private val _archivedIds = MutableStateFlow<Set<String>>(emptySet())
    val archivedIds: StateFlow<Set<String>> = _archivedIds.asStateFlow()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_ARCHIVED_IDS, emptySet())
            ?.map { normalizeKey(it) }
            ?.toSet()
            ?: emptySet()
        _archivedIds.value = saved
    }

    fun isArchived(peerOrGroupId: String): Boolean {
        val current = _archivedIds.value
        if (current.isEmpty()) return false
        val norm = normalizeKey(peerOrGroupId)
        if (current.contains(norm)) return true
        if (!norm.startsWith("group:")) {
            val aliases = runCatching {
                ContactDirectory.aliasesForConversation(peerOrGroupId)
            }.getOrDefault(emptySet())
            if (aliases.any { current.contains(it.trim().lowercase()) || current.contains(normalizeKey(it)) }) {
                return true
            }
        }
        return false
    }

    fun setArchived(context: Context, peerOrGroupId: String, archived: Boolean) {
        val appCtx = context.applicationContext
        val norm = normalizeKey(peerOrGroupId)
        val updated = _archivedIds.value.toMutableSet()
        if (archived) {
            updated.add(norm)
        } else {
            updated.remove(norm)
            if (!norm.startsWith("group:")) {
                val aliases = runCatching {
                    ContactDirectory.aliasesForConversation(peerOrGroupId)
                }.getOrDefault(emptySet())
                aliases.forEach { alias ->
                    updated.remove(alias.trim().lowercase())
                    updated.remove(normalizeKey(alias))
                }
            }
        }
        persist(appCtx, updated)
    }

    fun toggleArchived(context: Context, peerOrGroupId: String): Boolean {
        val next = !isArchived(peerOrGroupId)
        setArchived(context, peerOrGroupId, next)
        return next
    }

    fun archiveGroupKey(groupId: String): String = "group:${groupId.trim().lowercase()}"

    /**
     * Archives all non-archived 1-on-1 conversations and P2P groups whose last activity
     * is older than [maxAgeMs] milliseconds. Returns the number of chats newly archived.
     */
    fun archiveChatsOlderThan(
        context: Context,
        maxAgeMs: Long
    ): Int {
        val cutoffMs = System.currentTimeMillis() - maxAgeMs
        val updated = _archivedIds.value.toMutableSet()
        var count = 0

        val canonicalChats = runCatching {
            ContactDirectory.canonicalizePrivateChats(AppStateStore.privateMessages.value)
        }.getOrDefault(AppStateStore.privateMessages.value)

        canonicalChats.forEach { (convId, msgs) ->
            val lastMs = msgs.lastOrNull()?.timestamp?.time ?: 0L
            if (lastMs in 1..cutoffMs && !isArchived(convId)) {
                if (updated.add(normalizeKey(convId))) count++
            }
        }

        val groups = P2PGroupManager.groups.value
        val groupMessages = P2PGroupManager.groupMessages.value
        groups.forEach { grp ->
            val lastMs = groupMessages[grp.id]?.lastOrNull()?.timestamp ?: grp.createdAt
            val key = archiveGroupKey(grp.id)
            if (lastMs in 1..cutoffMs && !updated.contains(key)) {
                if (updated.add(key)) count++
            }
        }

        if (count > 0) {
            persist(context.applicationContext, updated)
        }
        return count
    }

    /**
     * Permanently deletes local chat history for 1-on-1 conversations and clears old group history
     * whose last activity is older than [maxAgeMs] milliseconds.
     */
    suspend fun deleteChatsOlderThan(
        context: Context,
        maxAgeMs: Long,
        onlyArchived: Boolean = false
    ): Int {
        val appCtx = context.applicationContext
        val cutoffMs = System.currentTimeMillis() - maxAgeMs
        val updatedArchived = _archivedIds.value.toMutableSet()
        var deletedCount = 0

        val canonicalChats = runCatching {
            ContactDirectory.canonicalizePrivateChats(AppStateStore.privateMessages.value)
        }.getOrDefault(AppStateStore.privateMessages.value)

        canonicalChats.forEach { (convId, msgs) ->
            val lastMs = msgs.lastOrNull()?.timestamp?.time ?: 0L
            val archived = isArchived(convId)
            if (lastMs in 1..cutoffMs && (!onlyArchived || archived)) {
                AppStateStore.deletePrivateConversationAndWait(convId)
                updatedArchived.remove(normalizeKey(convId))
                deletedCount++
            }
        }

        val groups = P2PGroupManager.groups.value
        val groupMessages = P2PGroupManager.groupMessages.value
        groups.forEach { grp ->
            val lastMs = groupMessages[grp.id]?.lastOrNull()?.timestamp ?: grp.createdAt
            val key = archiveGroupKey(grp.id)
            val archived = updatedArchived.contains(key)
            if (lastMs in 1..cutoffMs && (!onlyArchived || archived)) {
                P2PGroupManager.clearGroupHistoryLocally(appCtx, grp.id)
                updatedArchived.remove(key)
                deletedCount++
            }
        }

        persist(appCtx, updatedArchived)
        return deletedCount
    }

    fun clearAll(context: Context) {
        _archivedIds.value = emptySet()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun persist(appCtx: Context, set: Set<String>) {
        _archivedIds.value = set.toSet()
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ARCHIVED_IDS, set)
            .apply()
    }

    private fun normalizeKey(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.lowercase().startsWith("group:")) {
            return "group:" + trimmed.substringAfter(":").trim().lowercase()
        }
        return runCatching {
            ContactDirectory.canonicalConversationId(trimmed).lowercase()
        }.getOrDefault(trimmed.lowercase())
    }
}
