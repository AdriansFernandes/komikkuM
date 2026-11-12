package eu.kanade.presentation.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.interactor.SmartSearchMerge
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.tachiyomi.util.system.LocaleHelper
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK -->
/**
 * Preview & Confirmation sheet for integrating multiple manga entries (e.g., PT-BR and EN-US)
 * into a single Library entry with in-manga language switching.
 */
@Composable
fun MangaIntegrationPreviewDialog(
    mangas: List<Manga>,
    onDismissRequest: () -> Unit,
    onIntegrated: (Manga) -> Unit,
    onAddSeparately: (() -> Unit)? = null,
    candidateGroups: List<List<Manga>> = emptyList(),
) {
    val groups = remember(mangas, candidateGroups) {
        if (candidateGroups.isNotEmpty()) {
            candidateGroups
        } else {
            listOf(mangas.distinctBy { it.id })
        }
    }
    var activeGroupIndex by remember(groups) { mutableIntStateOf(0) }
    val activeMangas = groups.getOrElse(activeGroupIndex) { mangas.distinctBy { it.id } }

    val sourceManager = remember { Injekt.get<SourceManager>() }
    val getMergedMangaById = remember { Injekt.get<GetMergedMangaById>() }
    val getChaptersByMangaId = remember { Injekt.get<GetChaptersByMangaId>() }
    val smartSearchMerge = remember { Injekt.get<SmartSearchMerge>() }
    val scope = rememberCoroutineScope()

    var expandedChildren by remember(activeMangas) { mutableStateOf<List<Manga>>(emptyList()) }
    var chapterCounts by remember(activeMangas) { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var isIntegrating by remember { mutableStateOf(false) }
    var keepSingleBanner by remember { mutableStateOf(true) }

    val selectedMap = remember(activeMangas) {
        mutableStateMapOf<Long, Boolean>().apply {
            activeMangas.forEach { put(it.id, true) }
        }
    }

    var primaryInfoMangaId by remember(activeMangas) {
        mutableLongStateOf(
            activeMangas.firstOrNull { it.source != MERGED_SOURCE_ID }?.id
                ?: activeMangas.firstOrNull()?.id
                ?: -1L,
        )
    }

    LaunchedEffect(activeMangas) {
        withIOContext {
            val allChildren = mutableListOf<Manga>()
            val counts = mutableMapOf<Long, Int>()
            for (m in activeMangas) {
                if (m.source == MERGED_SOURCE_ID) {
                    val children = getMergedMangaById.await(m.id).filter { it.source != MERGED_SOURCE_ID }
                    allChildren.addAll(children)
                    counts[m.id] = children.sumOf { getChaptersByMangaId.await(it.id).size }
                    children.forEach { child ->
                        counts[child.id] = getChaptersByMangaId.await(child.id).size
                    }
                } else {
                    allChildren.add(m)
                    counts[m.id] = if (m.id > 0) getChaptersByMangaId.await(m.id).size else 0
                }
            }
            expandedChildren = allChildren.distinctBy { it.id }
            chapterCounts = counts
            if (primaryInfoMangaId == -1L || expandedChildren.none { it.id == primaryInfoMangaId }) {
                primaryInfoMangaId = expandedChildren.firstOrNull()?.id
                    ?: activeMangas.firstOrNull()?.id
                    ?: -1L
            }
        }
    }

    val chosenMangas = activeMangas.filter { selectedMap[it.id] == true }
    val displayMangas = if (expandedChildren.isNotEmpty()) {
        expandedChildren
    } else {
        activeMangas
    }
    val primaryManga = displayMangas.find { it.id == primaryInfoMangaId }
        ?: chosenMangas.firstOrNull()
        ?: activeMangas.firstOrNull()

    val minHeight = LocalPreferenceMinHeight.current
    val horizontalPadding = PaddingValues(horizontal = TabbedDialogPaddings.Horizontal)
    val horizontalPaddingModifier = Modifier.padding(horizontalPadding)

    AdaptiveSheet(
        onDismissRequest = {
            if (!isIntegrating) onDismissRequest()
        },
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            // Header
            Row(
                modifier = horizontalPaddingModifier.padding(top = MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.CallMerge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = stringResource(KMR.strings.manga_integration_preview_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            Text(
                text = stringResource(KMR.strings.manga_integration_preview_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                modifier = horizontalPaddingModifier.secondaryItemAlpha(),
            )

            // Group selector if scanning multiple groups in the library
            if (groups.size > 1) {
                Column(
                    modifier = horizontalPaddingModifier,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    Text(
                        text = stringResource(
                            KMR.strings.manga_integration_groups_found,
                            groups.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        groups.forEachIndexed { index, group ->
                            FilterChip(
                                selected = index == activeGroupIndex,
                                onClick = { activeGroupIndex = index },
                                label = {
                                    Text(
                                        text = "${index + 1}. ${group.firstOrNull()?.title.orEmpty()}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // Unified Library Banner Preview Card
            if (primaryManga != null) {
                Surface(
                    modifier = horizontalPaddingModifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(MaterialTheme.padding.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(KMR.strings.manga_integration_banner_preview_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
                        ) {
                            MangaCover.Book(
                                data = primaryManga,
                                modifier = Modifier.width(72.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = primaryManga.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!primaryManga.author.isNullOrBlank()) {
                                    Text(
                                        text = primaryManga.author!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.secondaryItemAlpha(),
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = stringResource(KMR.strings.manga_integration_available_languages),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.secondaryItemAlpha(),
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    displayMangas.forEach { itemManga ->
                                        val src = sourceManager.getOrStub(itemManga.source)
                                        val flag = FlagEmoji.getEmojiLangFlag(src.lang)
                                        val langCode = LocaleHelper.getShortDisplayName(src.lang).uppercase()
                                            .ifBlank { src.lang.uppercase() }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                        ) {
                                            Text(
                                                text = "$flag $langCode • ${src.name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = horizontalPaddingModifier)

            // List of entries to integrate
            Text(
                text = stringResource(KMR.strings.manga_integration_entries_header),
                style = MaterialTheme.typography.titleSmall,
                modifier = horizontalPaddingModifier,
            )

            Column(
                modifier = horizontalPaddingModifier,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                activeMangas.forEach { itemManga ->
                    val isChecked = selectedMap[itemManga.id] == true
                    val isMergedContainer = itemManga.source == MERGED_SOURCE_ID
                    val source = sourceManager.getOrStub(itemManga.source)
                    val flag = if (isMergedContainer) "🌐" else FlagEmoji.getEmojiLangFlag(source.lang)
                    val langDisplay = if (isMergedContainer) {
                        stringResource(MR.strings.multi_lang)
                    } else {
                        LocaleHelper.getLocalizedDisplayName(source.lang).ifBlank { source.lang.uppercase() }
                    }
                    val langShort = LocaleHelper.getShortDisplayName(source.lang).uppercase()
                    val isPrimary = primaryInfoMangaId == itemManga.id
                    val count = chapterCounts[itemManga.id] ?: 0

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = if (isPrimary) 1.5.dp else 0.dp,
                                color = if (isPrimary) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable {
                                selectedMap[itemManga.id] = !isChecked
                            }
                            .padding(MaterialTheme.padding.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { selectedMap[itemManga.id] = it },
                        )

                        MangaCover.Book(
                            data = itemManga,
                            modifier = Modifier.width(48.dp),
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = itemManga.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "$flag $langDisplay ($langShort) • ${source.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (count > 0) {
                                Text(
                                    text = stringResource(KMR.strings.manga_integration_chapters_count, count),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.secondaryItemAlpha(),
                                )
                            }
                        }

                        if (!isMergedContainer) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                RadioButton(
                                    selected = isPrimary,
                                    onClick = { primaryInfoMangaId = itemManga.id },
                                )
                                Text(
                                    text = stringResource(KMR.strings.manga_integration_primary_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.secondaryItemAlpha(),
                                )
                            }
                        }
                    }
                }
            }

            // Single banner option
            Row(
                modifier = horizontalPaddingModifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { keepSingleBanner = !keepSingleBanner }
                    .padding(vertical = MaterialTheme.padding.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Checkbox(
                    checked = keepSingleBanner,
                    onCheckedChange = { keepSingleBanner = it },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(KMR.strings.manga_integration_single_banner_option),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(KMR.strings.manga_integration_single_banner_summary),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.secondaryItemAlpha(),
                    )
                }
            }

            // Action buttons
            Column(
                modifier = horizontalPaddingModifier.padding(bottom = MaterialTheme.padding.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Button(
                    onClick = {
                        if (chosenMangas.size < 2 || isIntegrating) return@Button
                        isIntegrating = true
                        scope.launch {
                            val result = withIOContext {
                                smartSearchMerge.integrateMangas(
                                    mangasToMerge = chosenMangas,
                                    infoMangaId = primaryInfoMangaId.takeIf { it > 0 },
                                    hideChildrenFromLibrary = keepSingleBanner,
                                )
                            }
                            isIntegrating = false
                            onDismissRequest()
                            if (result != null) {
                                onIntegrated(result)
                            }
                        }
                    },
                    enabled = chosenMangas.size >= 2 && !isIntegrating,
                    modifier = Modifier
                        .heightIn(min = minHeight)
                        .fillMaxWidth(),
                ) {
                    if (isIntegrating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = stringResource(KMR.strings.manga_integration_confirm),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                if (onAddSeparately != null) {
                    OutlinedButton(
                        onClick = {
                            onDismissRequest()
                            onAddSeparately()
                        },
                        enabled = !isIntegrating,
                        modifier = Modifier
                            .heightIn(min = minHeight)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(MR.strings.action_add_anyway),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDismissRequest,
                    enabled = !isIntegrating,
                    modifier = Modifier
                        .heightIn(min = minHeight)
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(MR.strings.action_cancel),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
// KMK <--
