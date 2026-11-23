package com.example.ui.home

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.domain.model.Track
import com.example.playback.MusicPlayerManager
import com.example.ui.theme.LocalReducedMotion
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.ThemedCard
import com.example.ui.theme.bouncyClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Curated, real and currently popular anime songs. The *display* metadata is static so a card can
 * never pair a song with the wrong show or artist, while the playable [Track] itself is resolved at
 * runtime through the app's existing online search -- so we never ship brittle, region-locked video
 * ids that rot over time.
 */
private data class AnimeShowcaseItem(
    val anime: String,
    val title: String,
    val artist: String
) {
    val query: String get() = "$title $artist"
}

private val ShowcaseItems = listOf(
    AnimeShowcaseItem("Demon Slayer: Kimetsu no Yaiba", "Gurenge", "LiSA"),
    AnimeShowcaseItem("Oshi no Ko", "Idol", "YOASOBI"),
    AnimeShowcaseItem("Dandadan", "Otonoke", "Creepy Nuts"),
    AnimeShowcaseItem("Jujutsu Kaisen", "Kaikai Kitan", "Eve"),
    AnimeShowcaseItem("Chainsaw Man", "KICK BACK", "Kenshi Yonezu")
)

/** Process-lifetime cache, mirroring the discovery rails: search once, reuse for the session. */
private val showcaseTrackCache = mutableMapOf<String, Track>()

private const val ROTATE_INTERVAL_MS = 3_000L
private const val INTERACTION_COOLDOWN_MS = 9_000L

/**
 * Interactive "trending anime" showcase shown on the Claymorphism and Minimal home pages in place
 * of the Mikasa hero (which stays exclusive to Aurora Glass).
 *
 * Five real recommendations rotate roughly every three seconds, looping after the fifth. Every card
 * plays through the *same* [MusicPlayerManager] the rest of the app uses -- there is no second
 * player and no duplicated playback logic. Rotation is lifecycle-aware, it never swaps a card out
 * from under a song that is already playing, and it pauses for a short cooldown after the listener
 * touches the showcase. Under reduced-motion it becomes a static, manually navigable card.
 */
@Composable
fun AnimeTrendingShowcase(
    playerManager: MusicPlayerManager,
    onOpenFullSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val reducedMotion = LocalReducedMotion.current
    val playbackState by playerManager.playbackState.collectAsState()

    // Item index -> resolved, playable track. [unavailable] tracks items whose lookup failed so we
    // don't retry them and can offer a graceful "search instead" action on tap.
    val resolved = remember { mutableStateMapOf<Int, Track>() }
    val unavailable = remember { mutableStateListOf<Int>() }
    var index by remember { mutableStateOf(0) }
    var lastInteractionAt by remember { mutableStateOf(0L) }

    // Resolve each recommendation once. The repository caches results in its DB and we mirror that
    // in [showcaseTrackCache] for the process, so this runs at most five times per session.
    LaunchedEffect(Unit) {
        ShowcaseItems.forEachIndexed { i, item ->
            showcaseTrackCache[item.query]?.let {
                resolved[i] = it
                return@forEachIndexed
            }
            if (unavailable.contains(i)) return@forEachIndexed
            val track = runCatching { playerManager.repository.searchOnline(item.query) }
                .getOrNull()
                ?.getOrNull()
                ?.firstOrNull()
            if (track != null) {
                showcaseTrackCache[item.query] = track
                resolved[i] = track
            } else {
                unavailable += i
            }
        }
    }

    // If one of these songs is actively playing, lock the showcase to it: auto-advance must never
    // pull the card away from a song the listener is actually listening to. A merely paused track
    // does not lock, so the carousel never dead-ends on it.
    val activeTrackId = playbackState.currentTrack?.id
    val activeIsAudible = playbackState.isPlaying || playbackState.isLoading
    val lockedIndex = remember(resolved.size, activeTrackId, activeIsAudible) {
        if (activeTrackId == null || !activeIsAudible) -1
        else resolved.entries.firstOrNull { it.value.id == activeTrackId }?.key ?: -1
    }
    LaunchedEffect(lockedIndex) {
        if (lockedIndex >= 0) index = lockedIndex
    }

    // Lifecycle awareness: only advance while the screen is RESUMED. Navigating to the player or
    // locking the phone stops the timer, so nothing ticks in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locked by rememberUpdatedState(lockedIndex)
    val interactedAt by rememberUpdatedState(lastInteractionAt)
    LaunchedEffect(resumed, reducedMotion) {
        // Reduced-motion users get a static card they can page through manually instead.
        if (!resumed || reducedMotion) return@LaunchedEffect
        while (isActive) {
            delay(ROTATE_INTERVAL_MS)
            if (locked >= 0) continue
            if (SystemClock.elapsedRealtime() - interactedAt < INTERACTION_COOLDOWN_MS) continue
            index = (index + 1) % ShowcaseItems.size
        }
    }

    val visualIndex = ((index % ShowcaseItems.size) + ShowcaseItems.size) % ShowcaseItems.size

    fun playAt(target: Int) {
        lastInteractionAt = SystemClock.elapsedRealtime()
        index = target
        val track = resolved[target]
        when {
            track != null -> {
                val queue = ShowcaseItems.indices.mapNotNull { resolved[it] }
                playerManager.playTrack(track, queue.ifEmpty { listOf(track) })
            }
            // Lookup failed for this one -> hand off to the real search screen rather than faking
            // playback. Honest, and still one tap away from the song.
            unavailable.contains(target) -> onOpenFullSearch(ShowcaseItems[target].query)
            else -> Unit // still resolving; the card is showing a spinner
        }
    }

    fun toggleAt(target: Int) {
        val track = resolved[target]
        if (track != null && playbackState.currentTrack?.id == track.id) {
            lastInteractionAt = SystemClock.elapsedRealtime()
            playerManager.togglePlayPause()
        } else {
            playAt(target)
        }
    }

    fun selectIndex(target: Int) {
        lastInteractionAt = SystemClock.elapsedRealtime()
        index = target
    }

    ThemedCard(
        modifier = modifier.testTag("anime_showcase"),
        color = colors.surfaceCard
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            AnimatedContent(
                targetState = visualIndex,
                transitionSpec = {
                    if (colors.isClay) {
                        // Puffy, springy "clay" swap.
                        (slideInVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) { it / 5 } + fadeIn(tween(260))) togetherWith
                            (slideOutVertically(tween(220)) { -it / 5 } + fadeOut(tween(180)))
                    } else {
                        // Editorial cross-fade with a whisper of vertical travel.
                        (fadeIn(tween(440)) + slideInVertically(tween(440)) { it / 24 }) togetherWith
                            (fadeOut(tween(240)) + slideOutVertically(tween(440)) { -it / 24 })
                    }
                },
                label = "showcase_swap"
            ) { i ->
                val track = resolved[i]
                val isResolving = track == null && !unavailable.contains(i)
                val isCurrent = track != null && playbackState.currentTrack?.id == track.id
                val isPlayingThis = isCurrent && playbackState.isPlaying
                val isLoadingThis = isCurrent && playbackState.isLoading

                if (colors.isClay) {
                    ClayShowcaseCard(
                        item = ShowcaseItems[i],
                        artworkUrl = track?.artworkUrl,
                        isResolving = isResolving,
                        isPlaying = isPlayingThis,
                        isLoading = isLoadingThis,
                        onCardClick = { playAt(i) },
                        onPlayClick = { toggleAt(i) }
                    )
                } else {
                    MinimalShowcaseCard(
                        item = ShowcaseItems[i],
                        position = i + 1,
                        total = ShowcaseItems.size,
                        artworkUrl = track?.artworkUrl,
                        isResolving = isResolving,
                        isPlaying = isPlayingThis,
                        isLoading = isLoadingThis,
                        onCardClick = { playAt(i) },
                        onPlayClick = { toggleAt(i) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (colors.isClay) {
                ClayIndicator(
                    count = ShowcaseItems.size,
                    active = visualIndex,
                    onSelect = ::selectIndex
                )
            } else {
                MinimalIndicator(
                    position = visualIndex + 1,
                    total = ShowcaseItems.size,
                    onSelect = ::selectIndex
                )
            }
        }
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Claymorphism card: chunky radius, pastel surface, a fat candy play button that squishes.       */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun ClayShowcaseCard(
    item: AnimeShowcaseItem,
    artworkUrl: String?,
    isResolving: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    onCardClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val colors = SuzieeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(onClick = onCardClick)
            .testTag("anime_showcase_card"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShowcaseArtwork(
            artworkUrl = artworkUrl,
            isResolving = isResolving,
            size = 104.dp,
            corner = 28.dp,
            border = colors.accentSecondary.copy(alpha = 0.30f)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "TRENDING ANIME",
                color = colors.accentPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.anime,
                color = colors.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.title,
                color = colors.textPrimary,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.artist,
                color = colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        ClayPlayButton(
            isPlaying = isPlaying,
            isLoading = isLoading,
            onClick = onPlayClick
        )
    }
}

@Composable
private fun ClayPlayButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val colors = SuzieeTheme.colors
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(colors.accentGradient))
            .border(2.dp, Color.White.copy(alpha = 0.75f), CircleShape)
            .bouncyClickable(pressedScale = 0.88f, onClick = onClick)
            .testTag("anime_showcase_play"),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = colors.bgPrimary,
                strokeWidth = 2.dp
            )
            else -> Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = colors.bgPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun ClayIndicator(
    count: Int,
    active: Int,
    onSelect: (Int) -> Unit
) {
    val colors = SuzieeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            val selected = i == active
            val pipWidth by animateDpAsState(
                targetValue = if (selected) 22.dp else 8.dp,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
                label = "clay_pip_width"
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(pipWidth)
                    .clip(CircleShape)
                    .background(
                        if (selected) colors.accentPrimary
                        else colors.accentSecondary.copy(alpha = 0.28f)
                    )
                    .clickable { onSelect(i) }
                    .testTag("anime_showcase_dot_$i")
            )
        }
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Minimal Slate card: editorial grid, hairline rule, index counter, no candy.                   */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun MinimalShowcaseCard(
    item: AnimeShowcaseItem,
    position: Int,
    total: Int,
    artworkUrl: String?,
    isResolving: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    onCardClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val colors = SuzieeTheme.colors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TRENDING NOW",
                color = colors.textSecondary,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp)
            )
            Text(
                text = "${position.toString().padStart(2, '0')} / ${total.toString().padStart(2, '0')}",
                color = colors.textMuted,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bouncyClickable(pressedScale = 0.98f, onClick = onCardClick)
                .testTag("anime_showcase_card"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShowcaseArtwork(
                artworkUrl = artworkUrl,
                isResolving = isResolving,
                size = 84.dp,
                corner = 8.dp,
                border = colors.border
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.anime.uppercase(),
                    color = colors.accentPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.title,
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.artist,
                    color = colors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            MinimalPlayButton(
                isPlaying = isPlaying,
                isLoading = isLoading,
                onClick = onPlayClick
            )
        }
    }
}

@Composable
private fun MinimalPlayButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val colors = SuzieeTheme.colors
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .border(1.dp, colors.textPrimary.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick)
            .testTag("anime_showcase_play"),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = colors.accentPrimary,
                strokeWidth = 2.dp
            )
            else -> Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = colors.textPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun MinimalIndicator(
    position: Int,
    total: Int,
    onSelect: (Int) -> Unit
) {
    val colors = SuzieeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < position) colors.accentPrimary else colors.border)
                    .clickable { onSelect(i) }
                    .testTag("anime_showcase_dot_$i")
            )
        }
    }
}

/* --------------------------------------------------------------------------------------------- */

/**
 * Artwork for one recommendation. When the track has real cover art we show it; while the lookup is
 * still running (or if it failed) we fall back to the theme's own gradient and a small mark -- never
 * a fake or misleading image.
 */
@Composable
private fun ShowcaseArtwork(
    artworkUrl: String?,
    isResolving: Boolean,
    size: Dp,
    corner: Dp,
    border: Color
) {
    val colors = SuzieeTheme.colors
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(colors.accentGradient))
            .border(1.dp, border, shape),
        contentAlignment = Alignment.Center
    ) {
        if (artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(shape)
            )
        } else if (isResolving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = colors.bgPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = colors.bgPrimary.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
