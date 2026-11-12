package eu.kanade.domain.manga.interactor

import com.aallam.similarity.NormalizedLevenshtein
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

// KMK -->
/**
 * Finds cross-language matches (e.g. PT-BR, EN-US, Japanese Romaji/Native) in the user's Library
 * for a given [Manga] or scans the Library for candidate groups to integrate.
 *
 * Uses local titles, search metadata, description alt-title parsing, tracker titles,
 * and remote synonym resolution via AniList GraphQL and MangaDex public API.
 */
class FindCrossLanguageMangaMatches(
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getMergedMangaById: GetMergedMangaById = Injekt.get(),
    private val getSearchTitles: GetSearchTitles = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    private val levenshtein = NormalizedLevenshtein()
    private val remoteSynonymsCache = ConcurrentHashMap<String, Set<String>>()

    suspend operator fun invoke(manga: Manga, includeRemoteLookup: Boolean = true): List<MangaWithChapterCount> =
        withIOContext {
            val directDuplicates = getDuplicateLibraryManga(manga)
            val libraryItems = getLibraryManga.await()
            if (libraryItems.isEmpty()) return@withIOContext directDuplicates

            val targetRawTitles = collectLocalTitles(manga).toMutableSet()
            if (includeRemoteLookup) {
                targetRawTitles.addAll(fetchRemoteSynonyms(manga.title))
                if (manga.ogTitle.isNotBlank() && !manga.ogTitle.equals(manga.title, ignoreCase = true)) {
                    targetRawTitles.addAll(fetchRemoteSynonyms(manga.ogTitle))
                }
            }

            val targetNormTitles = targetRawTitles
                .map { normalizeTitle(it) }
                .filter { it.length >= 2 }
                .toSet()

            val targetNormAuthor = manga.author?.let { normalizeAuthor(it) }.orEmpty()

            val matchedById = LinkedHashMap<Long, MangaWithChapterCount>()
            directDuplicates.forEach { matchedById[it.manga.id] = it }

            for (libItem in libraryItems) {
                val libManga = libItem.manga
                if (libManga.id == manga.id || matchedById.containsKey(libManga.id)) continue

                val libRawTitles = collectLocalTitles(libManga)
                val libNormTitles = libRawTitles
                    .map { normalizeTitle(it) }
                    .filter { it.length >= 2 }
                    .toSet()

                val libNormAuthor = libManga.author?.let { normalizeAuthor(it) }.orEmpty()
                val sameAuthor = targetNormAuthor.length >= 4 &&
                    libNormAuthor.length >= 4 &&
                    (targetNormAuthor.contains(libNormAuthor) || libNormAuthor.contains(targetNormAuthor))

                if (areTitleSetsMatching(targetNormTitles, libNormTitles, sameAuthor)) {
                    matchedById[libManga.id] = MangaWithChapterCount(
                        manga = libManga,
                        chapterCount = libItem.totalChapters,
                    )
                }
            }

            matchedById.values.toList()
        }

    /**
     * Scans the current library to find groups of mangas in different languages/sources that
     * represent the same work and can be manually reviewed and integrated by the user.
     */
    suspend fun findLibraryIntegrationGroups(includeRemoteLookup: Boolean = true): List<List<Manga>> =
        withIOContext {
            val libraryItems = getLibraryManga.await()
            if (libraryItems.size < 2) return@withIOContext emptyList()

            val normTitlesPerManga = HashMap<Long, MutableSet<String>>()
            val normAuthorPerManga = HashMap<Long, String>()

            for (item in libraryItems) {
                val manga = item.manga
                val localTitles = collectLocalTitles(manga).toMutableSet()
                if (includeRemoteLookup && libraryItems.size <= 60) {
                    localTitles.addAll(fetchRemoteSynonyms(manga.title))
                }
                normTitlesPerManga[manga.id] = localTitles
                    .map { normalizeTitle(it) }
                    .filter { it.length >= 2 }
                    .toMutableSet()
                normAuthorPerManga[manga.id] = manga.author?.let { normalizeAuthor(it) }.orEmpty()
            }

            val visited = HashSet<Long>()
            val groups = mutableListOf<List<Manga>>()

            for (i in libraryItems.indices) {
                val base = libraryItems[i].manga
                if (!visited.add(base.id)) continue

                val baseTitles = normTitlesPerManga[base.id].orEmpty()
                val baseAuthor = normAuthorPerManga[base.id].orEmpty()
                val currentGroup = mutableListOf(base)

                for (j in i + 1 until libraryItems.size) {
                    val other = libraryItems[j].manga
                    if (other.id in visited) continue

                    val otherTitles = normTitlesPerManga[other.id].orEmpty()
                    val otherAuthor = normAuthorPerManga[other.id].orEmpty()
                    val sameAuthor = baseAuthor.length >= 4 &&
                        otherAuthor.length >= 4 &&
                        (baseAuthor.contains(otherAuthor) || otherAuthor.contains(baseAuthor))

                    if (areTitleSetsMatching(baseTitles, otherTitles, sameAuthor)) {
                        visited.add(other.id)
                        currentGroup.add(other)
                    }
                }

                if (currentGroup.size >= 2) {
                    groups.add(currentGroup)
                }
            }

            groups
        }

    private suspend fun collectLocalTitles(manga: Manga): Set<String> {
        val titles = mutableSetOf<String>()
        if (manga.title.isNotBlank()) titles.add(manga.title)
        if (manga.ogTitle.isNotBlank()) titles.add(manga.ogTitle)

        if (manga.id > 0) {
            runCatching {
                getSearchTitles.await(manga.id).forEach {
                    if (it.title.isNotBlank()) titles.add(it.title)
                }
            }
            runCatching {
                getTracks.await(manga.id).forEach {
                    if (it.title.isNotBlank()) titles.add(it.title)
                }
            }
            if (manga.source == MERGED_SOURCE_ID) {
                runCatching {
                    getMergedMangaById.await(manga.id).forEach { child ->
                        if (child.title.isNotBlank()) titles.add(child.title)
                        if (child.ogTitle.isNotBlank()) titles.add(child.ogTitle)
                    }
                }
            }
        }

        manga.description?.let { desc ->
            extractAltTitlesFromDescription(desc).forEach { titles.add(it) }
        }

        return titles
    }

    private fun extractAltTitlesFromDescription(description: String): List<String> {
        val results = mutableListOf<String>()
        val lines = description.lines()
        var inAltSection = false
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) {
                inAltSection = false
                continue
            }
            val headerMatch = ALT_TITLE_HEADER_REGEX.find(line)
            if (headerMatch != null) {
                val remainder = line.substring(headerMatch.range.last + 1).trim()
                if (remainder.isNotBlank()) {
                    remainder.split(';', '/', '|', ',')
                        .map { it.trim() }
                        .filter { it.length in 3..120 }
                        .forEach { results.add(it) }
                } else {
                    inAltSection = true
                }
            } else if (inAltSection && (line.startsWith("-") || line.startsWith("•") || line.startsWith("*"))) {
                val candidate = line.removePrefix("-").removePrefix("•").removePrefix("*").trim()
                if (candidate.length in 3..120) {
                    results.add(candidate)
                }
            } else if (inAltSection) {
                inAltSection = false
            }
        }
        return results
    }

    private suspend fun fetchRemoteSynonyms(rawTitle: String): Set<String> {
        val cleanQuery = rawTitle
            .replace(BRACKETS_REGEX, " ")
            .trim()
        if (cleanQuery.length < 2) return emptySet()

        val cacheKey = cleanQuery.lowercase()
        remoteSynonymsCache[cacheKey]?.let { return it }

        val discovered = withTimeoutOrNull(3500L) {
            coroutineScope {
                val anilistDeferred = async { queryAniListSynonyms(cleanQuery) }
                val mangaDexDeferred = async { queryMangaDexAltTitles(cleanQuery) }
                anilistDeferred.await() + mangaDexDeferred.await()
            }
        }.orEmpty()

        if (discovered.isNotEmpty()) {
            remoteSynonymsCache[cacheKey] = discovered
        }
        return discovered
    }

    private suspend fun queryAniListSynonyms(searchQuery: String): Set<String> {
        return try {
            val graphqlQuery = """
                query (${'$'}search: String) {
                  Page(page: 1, perPage: 3) {
                    media(search: ${'$'}search, type: MANGA) {
                      title {
                        romaji
                        english
                        native
                      }
                      synonyms
                    }
                  }
                }
            """.trimIndent()

            val payload = buildJsonObject {
                put("query", graphqlQuery)
                putJsonObject("variables") {
                    put("search", searchQuery)
                }
            }.toString()

            val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val responseStr = networkHelper.client.newCall(
                POST(
                    url = "https://graphql.anilist.co",
                    body = requestBody,
                ),
            ).awaitSuccess().use { it.body.string() }

            val root = json.parseToJsonElement(responseStr).jsonObject
            val mediaArray = root["data"]
                ?.jsonObject?.get("Page")
                ?.jsonObject?.get("media")
                ?.jsonArray
                ?: return emptySet()

            val queryNorm = normalizeTitle(searchQuery)
            val result = mutableSetOf<String>()

            for (mediaElem in mediaArray) {
                val mediaObj = mediaElem.jsonObject
                val titleObj = mediaObj["title"]?.jsonObject
                val candidates = mutableSetOf<String>()
                titleObj?.get("romaji")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(candidates::add)
                titleObj?.get("english")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(candidates::add)
                titleObj?.get("native")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(candidates::add)
                mediaObj["synonyms"]?.jsonArray?.forEach { syn ->
                    syn.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let(candidates::add)
                }

                // Verify that at least one title in this media entry is reasonably close to searchQuery
                val isRelevant = candidates.any { cand ->
                    val candNorm = normalizeTitle(cand)
                    candNorm == queryNorm ||
                        (candNorm.length >= 4 && queryNorm.length >= 4 && (candNorm.contains(queryNorm) || queryNorm.contains(candNorm))) ||
                        levenshtein.similarity(candNorm, queryNorm) >= 0.72
                }
                if (isRelevant) {
                    result.addAll(candidates)
                }
            }
            result
        } catch (_: Exception) {
            emptySet()
        }
    }

    private suspend fun queryMangaDexAltTitles(searchQuery: String): Set<String> {
        return try {
            val encoded = URLEncoder.encode(searchQuery, "UTF-8")
            val url = "https://api.mangadex.org/manga?title=$encoded&limit=3"
            val responseStr = networkHelper.client.newCall(GET(url)).awaitSuccess().use { it.body.string() }

            val root = json.parseToJsonElement(responseStr).jsonObject
            val dataArray = root["data"]?.jsonArray ?: return emptySet()
            val queryNorm = normalizeTitle(searchQuery)
            val result = mutableSetOf<String>()

            for (item in dataArray) {
                val attributes = item.jsonObject["attributes"]?.jsonObject ?: continue
                val candidates = mutableSetOf<String>()

                attributes["title"]?.jsonObject?.values?.forEach { valElem ->
                    valElem.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let(candidates::add)
                }

                (attributes["altTitles"] as? JsonArray)?.forEach { altElem ->
                    (altElem as? JsonObject)?.values?.forEach { valElem ->
                        valElem.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let(candidates::add)
                    }
                }

                val isRelevant = candidates.any { cand ->
                    val candNorm = normalizeTitle(cand)
                    candNorm == queryNorm ||
                        (candNorm.length >= 4 && queryNorm.length >= 4 && (candNorm.contains(queryNorm) || queryNorm.contains(candNorm))) ||
                        levenshtein.similarity(candNorm, queryNorm) >= 0.72
                }
                if (isRelevant) {
                    result.addAll(candidates)
                }
            }
            result
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun areTitleSetsMatching(
        titlesA: Set<String>,
        titlesB: Set<String>,
        sameAuthor: Boolean,
    ): Boolean {
        if (titlesA.isEmpty() || titlesB.isEmpty()) return false
        for (a in titlesA) {
            for (b in titlesB) {
                if (a == b) return true
                val minLen = minOf(a.length, b.length)
                val maxLen = maxOf(a.length, b.length)
                if (minLen >= 6 && maxLen > 0 && minLen.toDouble() / maxLen.toDouble() >= 0.65) {
                    if (a.contains(b) || b.contains(a)) return true
                }
                val similarity = levenshtein.similarity(a, b)
                if (similarity >= 0.80) return true
                if (sameAuthor && similarity >= 0.55) return true
            }
        }
        return false
    }

    private fun normalizeTitle(raw: String): String {
        val decomposed = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
        return decomposed
            .replace(BRACKETS_REGEX, " ")
            .replace(LANG_TAG_REGEX, " ")
            .replace(NON_ALPHANUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    private fun normalizeAuthor(raw: String): String {
        val decomposed = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
        return decomposed
            .replace(NON_ALPHANUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    companion object {
        private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()
        private val BRACKETS_REGEX = "(\\[.*?]|\\(.*?\\)|\\{.*?\\})".toRegex()
        private val LANG_TAG_REGEX = "\\b(pt[-_ ]?br|en[-_ ]?us|portugues|english|scan|oficial|official|colorido|colored)\\b".toRegex()
        private val NON_ALPHANUM_REGEX = "[^\\p{L}\\p{N}]+".toRegex()
        private val MULTI_SPACE_REGEX = "\\s+".toRegex()
        private val ALT_TITLE_HEADER_REGEX =
            "(?i)^(alt(ernative)?\\s*(title|name)s?|t[ií]tulos?\\s*alternativos?|nomes?\\s*alternativos?|associated\\s*names?|sin[oô]nimos?)\\s*:".toRegex()
    }
}
// KMK <--
