package cz.tadeasf.clipvault.ui

import cz.tadeasf.clipvault.history.ClipIngest
import cz.tadeasf.clipvault.model.ClipFilter
import cz.tadeasf.clipvault.model.ContentType
import cz.tadeasf.clipvault.settings.Settings
import cz.tadeasf.clipvault.settings.ThemeMode
import cz.tadeasf.clipvault.transform.Transformation
import cz.tadeasf.clipvault.ui.detail.DetailState
import cz.tadeasf.clipvault.ui.detail.DetailViewModel
import cz.tadeasf.clipvault.ui.history.HistoryState
import cz.tadeasf.clipvault.ui.history.HistoryViewModel
import cz.tadeasf.clipvault.ui.settings.SettingsState
import cz.tadeasf.clipvault.ui.settings.SettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    // History

    @Test
    fun historyIsEmptyWithoutClips() = test {
        val vm = HistoryViewModel(FakeClipRepository(), {})
        runCurrent()
        assertEquals(HistoryState.Empty, vm.state.value)
    }

    @Test
    fun historyCountsSensitiveClips() = test {
        val vm = HistoryViewModel(FakeClipRepository(clip("a"), clip("otp", sensitive = true)), {})
        runCurrent()
        assertEquals(HistoryState.Content(ClipFilter(), sensitiveCount = 1), vm.state.value)
    }

    @Test
    fun searchAndFiltersReachTheRepository() = test {
        val repo = FakeClipRepository(clip("a"))
        val vm = HistoryViewModel(repo, {})
        vm.onSearch("kot")
        vm.onTypeFilter(ContentType.URL)
        vm.onPinnedOnly(true)
        runCurrent()
        val expected = ClipFilter("kot", ContentType.URL, pinnedOnly = true)
        assertEquals(expected, vm.state.valueAs<HistoryState.Content>().filter)
        vm.clips.first()
        assertEquals(expected, repo.filters.last())
    }

    @Test
    fun revealedSensitiveClipsHideAgainAfterSixtySeconds() = test {
        val vm = HistoryViewModel(FakeClipRepository(clip("a"), clip("otp", sensitive = true)), {})
        vm.showSensitive()
        runCurrent()
        assertTrue(vm.state.valueAs<HistoryState.Content>().filter.includeSensitive)
        advanceTimeBy(HistoryViewModel.SENSITIVE_REVEAL_MILLIS - 1)
        assertTrue(vm.state.valueAs<HistoryState.Content>().filter.includeSensitive)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(vm.state.valueAs<HistoryState.Content>().filter.includeSensitive)
    }

    @Test
    fun hidingSensitiveEarlyCancelsTheTimer() = test {
        val vm = HistoryViewModel(FakeClipRepository(clip("a")), {})
        vm.showSensitive()
        vm.hideSensitive()
        vm.showSensitive()
        advanceTimeBy(HistoryViewModel.SENSITIVE_REVEAL_MILLIS / 2)
        runCurrent()
        assertTrue(vm.state.valueAs<HistoryState.Content>().filter.includeSensitive)
    }

    @Test
    fun copyAndDeleteReportBack() = test {
        val repo = FakeClipRepository(clip("a"), clip("b"))
        val copied = mutableListOf<String>()
        val vm = HistoryViewModel(repo, { copied += it.id })
        vm.copy(clip("a"))
        runCurrent()
        assertEquals(listOf("a"), copied)
        assertEquals("Copied to clipboard", vm.state.valueAs<HistoryState.Content>().message)
        vm.messageShown()
        vm.delete("b")
        runCurrent()
        assertTrue(repo.clips.value.getValue("b").deleted)
        assertEquals("Deleted", vm.state.valueAs<HistoryState.Content>().message)
    }

    // Detail

    private fun detail(repo: FakeClipRepository, id: String, platform: FakePlatform = FakePlatform()) =
        DetailViewModel(id, repo, ClipIngest(repo), platform)

    @Test
    fun detailLoadsClipAndWarnsBeforePinningSensitive() = test {
        val repo = FakeClipRepository(clip("a"), clip("otp", "483920", sensitive = true))
        val plain = detail(repo, "a")
        val sensitive = detail(repo, "otp")
        runCurrent()
        assertFalse(plain.state.valueAs<DetailState.Loaded>().pinWarning)
        assertTrue(sensitive.state.valueAs<DetailState.Loaded>().pinWarning)
        assertEquals(DetailState.Gone, detail(repo, "missing").also { runCurrent() }.state.value)
    }

    @Test
    fun detailPinsUnpinsAndDeletes() = test {
        val repo = FakeClipRepository(clip("a"))
        val vm = detail(repo, "a")
        runCurrent()
        vm.togglePin()
        runCurrent()
        assertTrue(vm.state.valueAs<DetailState.Loaded>().clip.pinned)
        assertEquals("Pinned", vm.state.valueAs<DetailState.Loaded>().message)
        vm.togglePin()
        runCurrent()
        assertFalse(vm.state.valueAs<DetailState.Loaded>().clip.pinned)
        vm.delete()
        runCurrent()
        assertEquals(DetailState.Gone, vm.state.value)
    }

    @Test
    fun transformationsCopyAndOptionallySave() = test {
        val repo = FakeClipRepository(clip("a", "  Hello  "), clip("s", "secret 483920", sensitive = true))
        val platform = FakePlatform()
        val vm = detail(repo, "a", platform)
        runCurrent()
        vm.apply(Transformation.Trim, saveAsNew = false)
        vm.apply(Transformation.UpperCase, saveAsNew = true)
        runCurrent()
        assertEquals(listOf("Hello" to false, "  HELLO  " to false), platform.copied)
        assertEquals(listOf("  HELLO  "), repo.inserted.map { it.content })
        vm.apply(Transformation.JsonPretty, saveAsNew = true)
        assertEquals("Not a JSON object or array", vm.state.valueAs<DetailState.Loaded>().message)
        assertEquals(2, platform.copied.size)

        val sensitive = detail(repo, "s", platform)
        runCurrent()
        sensitive.copy()
        assertEquals("secret 483920" to true, platform.copied.last()) // marked sensitive on the clipboard
    }

    @Test
    fun detailShares() = test {
        val platform = FakePlatform()
        val vm = detail(FakeClipRepository(clip("a", "share me")), "a", platform)
        runCurrent()
        vm.share()
        assertEquals(listOf("share me"), platform.shared)
    }

    // Settings

    @Test
    fun settingsPersistThroughTheRepository() = test {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo, FakePlatform())
        vm.setMaxCount(500)
        vm.setMaxAgeDays(0) // zero means "no limit"
        vm.setSensitiveTtlSeconds(300)
        vm.setCaptureNotification(true)
        vm.setTheme(ThemeMode.LIGHT)
        runCurrent()
        val expected = Settings(maxCount = 500, maxAgeDays = null, sensitiveTtlSeconds = 300, captureNotification = true, theme = ThemeMode.LIGHT)
        assertEquals(expected, repo.settings.value)
        assertEquals(expected, vm.state.valueAs<SettingsState.Loaded>().settings)
    }

    @Test
    fun ttlOutsideTheAllowedRangeIsIgnored() = test {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo, FakePlatform())
        vm.setSensitiveTtlSeconds(1)
        vm.setSensitiveTtlSeconds(99_999)
        runCurrent()
        assertEquals(Settings().sensitiveTtlSeconds, repo.settings.value.sensitiveTtlSeconds)
    }

    @Test
    fun exclusionsNeedAccessibility() = test {
        val platform = FakePlatform(accessibilityOn = false)
        val vm = SettingsViewModel(FakeSettingsRepository(), platform)
        runCurrent()
        assertFalse(vm.state.valueAs<SettingsState.Loaded>().exclusionsAvailable)
        assertEquals(emptyList(), vm.state.valueAs<SettingsState.Loaded>().apps)

        platform.accessibilityEnabled = true
        vm.refresh()
        vm.setExcluded("com.whatsapp", true)
        runCurrent()
        val loaded = vm.state.valueAs<SettingsState.Loaded>()
        assertTrue(loaded.exclusionsAvailable)
        assertEquals(2, loaded.apps.size)
        assertEquals(setOf("com.whatsapp"), loaded.settings.excludedPackages)
        vm.setExcluded("com.whatsapp", false)
        runCurrent()
        assertEquals(emptySet(), vm.state.valueAs<SettingsState.Loaded>().settings.excludedPackages)
    }

    @Test
    fun desktopHasNoAccessibilitySection() = test {
        val vm = SettingsViewModel(FakeSettingsRepository(), FakePlatform(accessibilityOn = null))
        runCurrent()
        assertEquals(null, vm.state.valueAs<SettingsState.Loaded>().accessibilityEnabled)
    }

    @Test
    fun formatting() {
        assertEquals("just now", relativeTime(100_000, 90_000))
        assertEquals("5 min ago", relativeTime(5 * 60_000L, 0))
        assertEquals("3 h ago", relativeTime(3 * 3_600_000L, 0))
        assertEquals("2 d ago", relativeTime(2 * 86_400_000L, 0))
        assertEquals("a\nb", preview("a\nb\nc"))
        val labels = cz.tadeasf.clipvault.transform.transformations.map { it.label() }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(ContentType.entries.size, ContentType.entries.map { it.glyph() }.toSet().size)
    }
}
