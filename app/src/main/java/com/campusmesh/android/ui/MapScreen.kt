package com.campusmesh.android.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.android.core.ui.component.avatar.InitialsAvatar
import com.campusmesh.android.core.ui.component.avatar.avatarColorFor
import com.campusmesh.android.core.ui.component.avatar.avatarColorForIndex
import com.campusmesh.android.core.ui.component.avatar.initialsFor
import com.campusmesh.android.onboarding.GhostModePreferenceManager
import com.campusmesh.android.services.AppStateStore
import com.campusmesh.android.ui.theme.AvatarPreferenceManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

// GCTU Main Campus (Tesano), per docs/CAMPUS_MESH_IMPLEMENTATION_PLAN.md §2.1.
private const val MAIN_CAMPUS_LAT = 5.5961352
private const val MAIN_CAMPUS_LNG = -0.2234766

/**
 * Campus Map - new screen (docs/UI_REDESIGN_IMPLEMENTATION_PLAN.md Phase 6). Renders a real
 * OpenStreetMap view (via the same Leaflet + Carto-tiles setup already used by
 * GeohashPickerActivity/geohash_picker.html in this app) centered on Main Campus, with markers
 * for everyone currently reachable - both direct mesh (BLE/Wi-Fi Aware) peers and Main Campus
 * geohash channel participants - merged and deduped by ID.
 *
 * There is no real per-peer GPS broadcast anywhere in this app (a deliberate privacy choice - see
 * GhostModePreferenceManager's docs), so peer markers are placed at a small deterministic offset
 * from the campus center rather than an actual live position - this shows "who's around campus
 * right now," not a precise tracker. Once real position broadcast exists this only needs the
 * jitterOffset() call swapped for real coordinates; the map plumbing itself doesn't change.
 *
 * Ghost Mode's toggle lives here (not in Settings, per the plan's resolved review comment) since
 * this is the one place its effect is actually visible: enabling it hides your own "You" marker.
 *
 * Reached from the map-pin icon in the chat-list header, or "View on map" in the Channels & Nearby
 * sheet. Back returns to the chat list (ChatViewModel.openMap/closeMap).
 */
@Composable
fun MapScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val avatarColorIndex by AvatarPreferenceManager.colorIndexFlow.collectAsStateWithLifecycle()
    val isGhostMode by AppStateStore.isGhostMode.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<MapPerson?>(null) }

    val people = remember(connectedPeers, peerNicknames, geohashPeople) {
        val fromMesh = connectedPeers.map { id -> MapPerson(id, peerNicknames[id]?.takeIf { it.isNotBlank() } ?: id.take(6)) }
        val fromGeohash = geohashPeople.map { MapPerson(it.id, it.displayName) }
        (fromMesh + fromGeohash).distinctBy { it.id }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                // Edge-to-edge theme needs explicit status-bar padding (same fix as
                // ChatListScreen.kt/SettingsScreen.kt's headers).
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.closeMap() }) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Campus map",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "${people.size} people nearby · Main Campus",
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 10.5.sp
                )
            }
            Icon(
                imageVector = Icons.Filled.VisibilityOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
            )
            Switch(
                checked = isGhostMode,
                onCheckedChange = { enabled ->
                    GhostModePreferenceManager.setEnabled(context, enabled)
                },
                modifier = Modifier.padding(start = 4.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.secondary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f)
                )
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            CampusOsmMap(
                people = people,
                selfNickname = nickname,
                selfColor = avatarColorForIndex(avatarColorIndex) ?: avatarColorFor(nickname),
                showSelf = !isGhostMode,
                onPersonTap = { selected = it }
            )

            if (isGhostMode) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Ghost Mode on - you're hidden",
                        modifier = Modifier.padding(start = 6.dp),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Fully qualified: bare AnimatedVisibility() here resolves ambiguously to
            // ColumnScope.AnimatedVisibility (this Box is a direct child of the outer Column) and
            // fails with "cannot be called in this context with an implicit receiver" - a known
            // Compose Animation gotcha, not a real compile error in the logic.
            androidx.compose.animation.AnimatedVisibility(
                visible = selected != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                selected?.let { person ->
                    MarkerCard(
                        person = person,
                        onDismiss = { selected = null },
                        onMessage = {
                            viewModel.startPrivateChat(person.id)
                            viewModel.openConversation()
                            viewModel.closeMap()
                        }
                    )
                }
            }
        }
    }
}

private data class MapPerson(val id: String, val name: String)

/**
 * Hosts campus_map.html (Leaflet + OpenStreetMap/Carto tiles) in a WebView, same pattern as
 * GeohashPickerActivity's WebView setup. Pushes people/self state into the page via
 * evaluateJavascript once the page has finished loading, and receives marker-tap events back via
 * a @JavascriptInterface bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CampusOsmMap(
    people: List<MapPerson>,
    selfNickname: String,
    selfColor: Color,
    showSelf: Boolean,
    onPersonTap: (MapPerson) -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    // Long-lived closures (the JS bridge, the WebViewClient) are created once in `factory` and
    // must always see the latest people/onPersonTap - rememberUpdatedState is the standard fix
    // for that (plain captured vals would otherwise be frozen at first composition).
    val latestPeople = rememberUpdatedState(people)
    val latestOnPersonTap = rememberUpdatedState(onPersonTap)

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        val theme = if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
                        evaluateJavascript("window.setMapTheme('$theme')", null)
                        evaluateJavascript("window.focusCampus($MAIN_CAMPUS_LAT, $MAIN_CAMPUS_LNG, 17)", null)
                        pageLoaded = true
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onMarkerTapped(id: String) {
                        val person = latestPeople.value.find { it.id == id } ?: return
                        post { latestOnPersonTap.value(person) }
                    }
                }, "Android")

                loadUrl("file:///android_asset/campus_map.html")
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { webView ->
            webViewRef = webView
            webView.updateLayoutParams<ViewGroup.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        },
        onRelease = { webView ->
            // Best-effort cleanup, same as GeohashPickerActivity - this screen can be opened and
            // closed repeatedly within the same app session, unlike a whole Activity.
            try { webView.evaluateJavascript("window.cleanup && window.cleanup()", null) } catch (_: Throwable) {}
            try { webView.stopLoading() } catch (_: Throwable) {}
            try { webView.clearHistory() } catch (_: Throwable) {}
            try { webView.clearCache(true) } catch (_: Throwable) {}
            try { webView.loadUrl("about:blank") } catch (_: Throwable) {}
            try { webView.removeAllViews() } catch (_: Throwable) {}
            try { webView.destroy() } catch (_: Throwable) {}
        }
    )

    // Push current state into the page whenever it changes, but only once the page has actually
    // finished loading (window.setSelf/setPeople don't exist before then).
    LaunchedEffect(webViewRef, pageLoaded, people, showSelf, selfColor, selfNickname) {
        val webView = webViewRef ?: return@LaunchedEffect
        if (!pageLoaded) return@LaunchedEffect

        val selfInitials = initialsFor(selfNickname)
        webView.evaluateJavascript(
            "window.setSelf($MAIN_CAMPUS_LAT, $MAIN_CAMPUS_LNG, $showSelf, " +
                "${JSONObject.quote(selfInitials)}, ${JSONObject.quote(selfColor.toCssHex())})",
            null
        )

        val peopleJson = JSONArray().apply {
            people.forEach { person ->
                val (dLat, dLng) = jitterOffset(person.id)
                put(
                    JSONObject().apply {
                        put("id", person.id)
                        put("initials", initialsFor(person.name))
                        put("color", avatarColorFor(person.id).toCssHex())
                        put("lat", MAIN_CAMPUS_LAT + dLat)
                        put("lng", MAIN_CAMPUS_LNG + dLng)
                    }
                )
            }
        }
        webView.evaluateJavascript("window.setPeople(${JSONObject.quote(peopleJson.toString())})", null)
    }
}

@Composable
private fun MarkerCard(
    person: MapPerson,
    onDismiss: () -> Unit,
    onMessage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)), RoundedCornerShape(16.dp))
            .clickable(onClick = onDismiss)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InitialsAvatar(seed = person.id, displayNickname = person.name, size = 40.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(
                text = person.name,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "On Main Campus mesh",
                fontFamily = FontFamily.SansSerif,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
                .clickable(onClick = onMessage)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = "Message",
                modifier = Modifier.padding(start = 5.dp),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/** #RRGGBB, for embedding this Compose color into the Leaflet HTML/CSS marker. */
private fun Color.toCssHex(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", r, g, b)
}

/**
 * Deterministic small lat/lng offset (roughly 40-140m from campus center), stable per person ID.
 * Stand-in for real position data, which doesn't exist yet (see class doc above) - keeps markers
 * visually spread out around campus rather than all stacked on one point.
 */
private fun jitterOffset(seed: String): Pair<Double, Double> {
    val h = abs(seed.hashCode())
    val angle = (h % 360) * (Math.PI / 180.0)
    val radiusMeters = 40.0 + (h / 360 % 100)
    val dLat = (radiusMeters * cos(angle)) / 111_320.0
    val dLng = (radiusMeters * sin(angle)) / (111_320.0 * cos(Math.toRadians(MAIN_CAMPUS_LAT)))
    return dLat to dLng
}
