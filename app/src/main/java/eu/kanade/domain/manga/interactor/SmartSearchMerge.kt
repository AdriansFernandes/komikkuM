package eu.kanade.domain.manga.interactor

import android.app.Application
import eu.kanade.domain.manga.model.copyFrom
import eu.kanade.domain.manga.model.toSManga
import exh.source.MERGED_SOURCE_ID
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.interactor.DeleteByMergeId
import tachiyomi.domain.manga.interactor.DeleteMangaById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.InsertMergedReference
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.UpdateMergedSettings
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MergeMangaSettingsUpdate
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SmartSearchMerge(
    private val getManga: GetManga = Injekt.get(),
    private val getMergedReferencesById: GetMergedReferencesById = Injekt.get(),
    private val insertMergedReference: InsertMergedReference = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val deleteMangaById: DeleteMangaById = Injekt.get(),
    private val deleteByMergeId: DeleteByMergeId = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    // KMK -->
    private val updateManga: UpdateManga = Injekt.get(),
    private val updateMergedSettings: UpdateMergedSettings = Injekt.get(),
    // KMK <--
) {
    /**
     * @param originalMangaId ID of the existed merged entry or the original manga which will be used to create the new merged entry
     * @param manga The manga which will be merged into existed merged entry or the new merged entry (newly created by using [originalMangaId])
     */
    suspend fun smartSearchMerge(manga: Manga, originalMangaId: Long): Manga {
        // KMK -->
        val context = Injekt.get<Application>()
        // KMK <--
        val originalManga = getManga.await(originalMangaId)
            ?: throw IllegalArgumentException(context.stringResource(SYMR.strings.merge_unknown_entry, originalMangaId))
        if (originalManga.source == MERGED_SOURCE_ID) {
            val children = getMergedReferencesById.await(originalMangaId)
            if (children.any { it.mangaSourceId == manga.source && it.mangaUrl == manga.url }) {
                // Merged already
                return originalManga
            }

            val mangaReferences = mutableListOf(
                MergedMangaReference(
                    id = -1,
                    isInfoManga = false,
                    getChapterUpdates = true,
                    chapterSortMode = 0,
                    chapterPriority = 0,
                    downloadChapters = true,
                    mergeId = originalManga.id,
                    mergeUrl = originalManga.url,
                    mangaId = manga.id,
                    mangaUrl = manga.url,
                    mangaSourceId = manga.source,
                ),
            )

            if (children.isEmpty() || children.all { it.mangaSourceId != MERGED_SOURCE_ID }) {
                mangaReferences += MergedMangaReference(
                    id = -1,
                    isInfoManga = false,
                    getChapterUpdates = false,
                    chapterSortMode = 0,
                    chapterPriority = -1,
                    downloadChapters = false,
                    mergeId = originalManga.id,
                    mergeUrl = originalManga.url,
                    mangaId = originalManga.id,
                    mangaUrl = originalManga.url,
                    mangaSourceId = MERGED_SOURCE_ID,
                )
            }

            // todo
            insertMergedReference.awaitAll(mangaReferences)

            return originalManga
        } else {
            if (manga.id == originalMangaId) {
                // Merged already
                return originalManga
            }
            var mergedManga = Manga.create()
                .copy(
                    url = originalManga.url,
                    ogTitle = originalManga.title,
                    source = MERGED_SOURCE_ID,
                )
                .copyFrom(originalManga.toSManga())
                .copy(
                    favorite = true,
                    lastUpdate = originalManga.lastUpdate,
                    viewerFlags = originalManga.viewerFlags,
                    chapterFlags = originalManga.chapterFlags,
                    dateAdded = System.currentTimeMillis(),
                )

            var existingManga = getManga.await(mergedManga.url, mergedManga.source)
            while (existingManga != null) {
                if (existingManga.favorite) {
                    // Duplicate entry found -> use it instead
                    mergedManga = existingManga
                    break
                } else {
                    withNonCancellableContext {
                        existingManga?.id?.let {
                            deleteByMergeId.await(it)
                            deleteMangaById.await(it)
                        }
                    }
                }
                // Remove previously merged entry from database (user already removed from favorites)
                existingManga = getManga.await(mergedManga.url, mergedManga.source)
            }

            mergedManga = networkToLocalManga(mergedManga)

            getCategories.await(originalMangaId)
                .let { categories ->
                    setMangaCategories.await(mergedManga.id, categories.map { it.id })
                }

            val originalMangaReference = MergedMangaReference(
                id = -1,
                isInfoManga = true,
                getChapterUpdates = true,
                chapterSortMode = 0,
                chapterPriority = 0,
                downloadChapters = true,
                mergeId = mergedManga.id,
                mergeUrl = mergedManga.url,
                mangaId = originalManga.id,
                mangaUrl = originalManga.url,
                mangaSourceId = originalManga.source,
            )

            val newMangaReference = MergedMangaReference(
                id = -1,
                isInfoManga = false,
                getChapterUpdates = true,
                chapterSortMode = 0,
                chapterPriority = 0,
                downloadChapters = true,
                mergeId = mergedManga.id,
                mergeUrl = mergedManga.url,
                mangaId = manga.id,
                mangaUrl = manga.url,
                mangaSourceId = manga.source,
            )

            val mergedMangaReference = MergedMangaReference(
                id = -1,
                isInfoManga = false,
                getChapterUpdates = false,
                chapterSortMode = 0,
                chapterPriority = -1,
                downloadChapters = false,
                mergeId = mergedManga.id,
                mergeUrl = mergedManga.url,
                mangaId = mergedManga.id,
                mangaUrl = mergedManga.url,
                mangaSourceId = MERGED_SOURCE_ID,
            )

            insertMergedReference.awaitAll(listOf(originalMangaReference, newMangaReference, mergedMangaReference))

            return mergedManga
        }

        // Note that if the manga are merged in a different order, this won't trigger, but I don't care lol
    }

    // KMK -->
    data class BatchIntegrationGroup(
        val mangasToMerge: List<Manga>,
        val infoMangaId: Long? = null,
    )

    /**
     * Integrates multiple groups of mangas in a single fast batch, deferring the Library
     * favorite visibility updates (`MangaUpdate`) to a single `updateManga.awaitAll` call at the end
     * so the Library UI does not re-query and freeze on every intermediate step.
     */
    suspend fun integrateMangaGroupsBatch(
        groups: List<BatchIntegrationGroup>,
        hideChildrenFromLibrary: Boolean = true,
        onProgress: (completed: Int, total: Int, currentTitle: String) -> Unit = { _, _, _ -> },
    ): List<Manga> {
        if (groups.isEmpty()) return emptyList()
        val mergedResults = mutableListOf<Manga>()
        val pendingMangaUpdates = LinkedHashMap<Long, MangaUpdate>()

        for ((index, group) in groups.withIndex()) {
            val distinctMangas = group.mangasToMerge.distinctBy { it.id }
            if (distinctMangas.size < 2) continue

            val primaryTitle = distinctMangas.find { it.id == group.infoMangaId }?.title
                ?: distinctMangas.first().title
            onProgress(index + 1, groups.size, primaryTitle)

            val result = integrateSingleGroupInternal(
                distinctMangas = distinctMangas,
                infoMangaId = group.infoMangaId,
                hideChildrenFromLibrary = hideChildrenFromLibrary,
                deferredMangaUpdates = pendingMangaUpdates,
            )
            if (result != null) {
                mergedResults.add(result)
            }
        }

        if (pendingMangaUpdates.isNotEmpty()) {
            updateManga.awaitAll(pendingMangaUpdates.values.toList())
        }

        return mergedResults
    }

    /**
     * Integrates multiple manga entries (e.g. PT-BR and EN-US versions) into a single unified
     * [MERGED_SOURCE_ID] entry, optionally hiding individual child entries from the Library so
     * only one banner remains in the user's Library list.
     */
    suspend fun integrateMangas(
        mangasToMerge: List<Manga>,
        infoMangaId: Long? = null,
        hideChildrenFromLibrary: Boolean = true,
    ): Manga? {
        val distinctMangas = mangasToMerge.distinctBy { it.id }
        if (distinctMangas.size <= 1) return distinctMangas.firstOrNull()

        val pendingUpdates = LinkedHashMap<Long, MangaUpdate>()
        val merged = integrateSingleGroupInternal(
            distinctMangas = distinctMangas,
            infoMangaId = infoMangaId,
            hideChildrenFromLibrary = hideChildrenFromLibrary,
            deferredMangaUpdates = pendingUpdates,
        )
        if (pendingUpdates.isNotEmpty()) {
            updateManga.awaitAll(pendingUpdates.values.toList())
        }
        return merged
    }

    private suspend fun integrateSingleGroupInternal(
        distinctMangas: List<Manga>,
        infoMangaId: Long?,
        hideChildrenFromLibrary: Boolean,
        deferredMangaUpdates: MutableMap<Long, MangaUpdate>,
    ): Manga? {
        val existingMerged = distinctMangas.firstOrNull { it.source == MERGED_SOURCE_ID }
        val nonMerged = distinctMangas.filterNot { it.source == MERGED_SOURCE_ID }
            .sortedByDescending { it.id == infoMangaId }

        val targetInfoManga = nonMerged.firstOrNull() ?: existingMerged ?: return null

        val mergedManga: Manga = if (existingMerged != null) {
            existingMerged
        } else {
            var candidate = Manga.create()
                .copy(
                    url = targetInfoManga.url,
                    ogTitle = targetInfoManga.title,
                    source = MERGED_SOURCE_ID,
                )
                .copyFrom(targetInfoManga.toSManga())
                .copy(
                    favorite = true,
                    lastUpdate = targetInfoManga.lastUpdate,
                    viewerFlags = targetInfoManga.viewerFlags,
                    chapterFlags = targetInfoManga.chapterFlags,
                    dateAdded = System.currentTimeMillis(),
                )

            var existingInDb = getManga.await(candidate.url, candidate.source)
            while (existingInDb != null) {
                if (existingInDb.favorite) {
                    candidate = existingInDb
                    break
                } else {
                    withNonCancellableContext {
                        existingInDb?.id?.let {
                            deleteByMergeId.await(it)
                            deleteMangaById.await(it)
                        }
                    }
                }
                existingInDb = getManga.await(candidate.url, candidate.source)
            }
            networkToLocalManga(candidate)
        }

        val existingRefs = getMergedReferencesById.await(mergedManga.id)
        val newReferences = mutableListOf<MergedMangaReference>()

        if (existingRefs.none { it.mangaSourceId == MERGED_SOURCE_ID }) {
            newReferences += MergedMangaReference(
                id = -1,
                isInfoManga = false,
                getChapterUpdates = false,
                chapterSortMode = 0,
                chapterPriority = -1,
                downloadChapters = false,
                mergeId = mergedManga.id,
                mergeUrl = mergedManga.url,
                mangaId = mergedManga.id,
                mangaUrl = mergedManga.url,
                mangaSourceId = MERGED_SOURCE_ID,
            )
        }

        for (child in nonMerged) {
            val alreadyLinked = existingRefs.any {
                it.mangaId == child.id || (it.mangaSourceId == child.source && it.mangaUrl == child.url)
            }
            if (!alreadyLinked) {
                newReferences += MergedMangaReference(
                    id = -1,
                    isInfoManga = child.id == targetInfoManga.id && existingRefs.none { it.isInfoManga },
                    getChapterUpdates = true,
                    chapterSortMode = 0,
                    chapterPriority = 0,
                    downloadChapters = true,
                    mergeId = mergedManga.id,
                    mergeUrl = mergedManga.url,
                    mangaId = child.id,
                    mangaUrl = child.url,
                    mangaSourceId = child.source,
                )
            }
        }

        if (newReferences.isNotEmpty()) {
            insertMergedReference.awaitAll(newReferences)
        }

        // Union all categories in one single call
        val allCategoryIds = distinctMangas
            .flatMap { getCategories.await(it.id) }
            .map { it.id }
            .distinct()
        if (allCategoryIds.isNotEmpty()) {
            setMangaCategories.await(mergedManga.id, allCategoryIds)
        }

        val allRefs = if (existingRefs.isNotEmpty() && infoMangaId != null) {
            val refs = getMergedReferencesById.await(mergedManga.id)
            val settingsUpdates = refs.map { ref ->
                MergeMangaSettingsUpdate(
                    id = ref.id,
                    isInfoManga = ref.mangaId == targetInfoManga.id,
                    getChapterUpdates = ref.getChapterUpdates,
                    chapterPriority = ref.chapterPriority,
                    downloadChapters = ref.downloadChapters,
                    chapterSortMode = ref.chapterSortMode,
                )
            }
            updateMergedSettings.awaitAll(settingsUpdates)
            refs
        } else {
            existingRefs
        }

        if (hideChildrenFromLibrary) {
            val childIds = (nonMerged.map { it.id } + allRefs.mapNotNull { it.mangaId })
                .filter { it != mergedManga.id && it > 0 }
                .distinct()
            for (childId in childIds) {
                deferredMangaUpdates[childId] = MangaUpdate(
                    id = childId,
                    favorite = false,
                )
            }
        }

        deferredMangaUpdates[mergedManga.id] = MangaUpdate(
            id = mergedManga.id,
            title = targetInfoManga.title.takeIf { existingMerged != null },
            thumbnailUrl = targetInfoManga.thumbnailUrl.takeIf { existingMerged != null },
            author = targetInfoManga.author.takeIf { existingMerged != null },
            artist = targetInfoManga.artist.takeIf { existingMerged != null },
            description = targetInfoManga.description.takeIf { existingMerged != null },
            genre = targetInfoManga.genre.takeIf { existingMerged != null },
            status = targetInfoManga.status.takeIf { existingMerged != null },
            favorite = true,
        )

        return mergedManga
    }
    // KMK <--
}
