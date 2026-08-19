package eu.kanade.tachiyomi.ui.browse.extension.details

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.forEach
import androidx.preference.getOnBindEditTextListener
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.screen.advanced.DomainForwardingDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText.Companion.setIncognito
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as contextStringResource

class SourcePreferencesScreen(val sourceId: Long) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        var domainForwardingSource by rememberSaveable { mutableStateOf<String?>(null) }

        domainForwardingSource?.let { source ->
            DomainForwardingDialog(
                manager = Injekt.get<NetworkHelper>().domainForwarding,
                initialSource = source,
                lockSource = true,
                onDismissRequest = { domainForwardingSource = null },
            )
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = Injekt.get<SourceManager>().getOrStub(sourceId).toString(),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            FragmentContainer(
                fragmentManager = (context as FragmentActivity).supportFragmentManager,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                add(
                    it,
                    SourcePreferencesFragment.getInstance(sourceId).apply {
                        onDomainForwardingClick = { domainForwardingSource = it }
                    },
                    null,
                )
            }
        }
    }

    /**
     * From https://stackoverflow.com/questions/60520145/fragment-container-in-jetpack-compose/70817794#70817794
     */
    @Composable
    private fun FragmentContainer(
        fragmentManager: FragmentManager,
        modifier: Modifier = Modifier,
        commit: FragmentTransaction.(containerId: Int) -> Unit,
    ) {
        val containerId by rememberSaveable {
            mutableIntStateOf(View.generateViewId())
        }
        var initialized by rememberSaveable { mutableStateOf(false) }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                FragmentContainerView(context)
                    .apply { id = containerId }
            },
            update = { view ->
                if (!initialized) {
                    fragmentManager.commit { commit(view.id) }
                    initialized = true
                } else {
                    fragmentManager.onContainerAvailable(view)
                }
            },
        )
    }

    /** Access to package-private method in FragmentManager through reflection */
    private fun FragmentManager.onContainerAvailable(view: FragmentContainerView) {
        val method = FragmentManager::class.java.getDeclaredMethod(
            "onContainerAvailable",
            FragmentContainerView::class.java,
        )
        method.isAccessible = true
        method.invoke(this, view)
    }
}

class SourcePreferencesFragment : PreferenceFragmentCompat() {

    var onDomainForwardingClick: ((String) -> Unit)? = null

    override fun getContext(): Context? {
        val superCtx = super.getContext() ?: return null
        val tv = TypedValue()
        superCtx.theme.resolveAttribute(R.attr.preferenceTheme, tv, true)
        return ContextThemeWrapper(superCtx, tv.resourceId)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = populateScreen()
    }

    private fun populateScreen(): PreferenceScreen {
        val sourceId = requireArguments().getLong(SOURCE_ID)
        val source = Injekt.get<SourceManager>().getOrStub(sourceId)
        val libraryPreferences: LibraryPreferences = Injekt.get()
        val sourceScreen = preferenceManager.createPreferenceScreen(requireContext())

        val reverseChapterPref = SwitchPreferenceCompat(requireContext()).apply {
            key = "reverse_chapter_list_$sourceId"
            title = requireContext().contextStringResource(TDMR.strings.source_pref_reverse_chapter_list_title)
            summary = requireContext().contextStringResource(TDMR.strings.source_pref_reverse_chapter_list_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false

            // Initialize from preference
            val reversedSources = libraryPreferences.reversedChapterSources.get()
            isChecked = sourceId.toString() in reversedSources

            setOnPreferenceChangeListener { _, newValue ->
                val reversed = newValue as Boolean
                val currentSet = libraryPreferences.reversedChapterSources.get()
                libraryPreferences.reversedChapterSources.set(
                    if (reversed) {
                        currentSet + sourceId.toString()
                    } else {
                        currentSet - sourceId.toString()
                    },
                )
                true
            }
        }
        sourceScreen.addPreference(reverseChapterPref)

        if (source is JsSource) {
            sourceScreen.addPreference(
                androidx.preference.Preference(requireContext()).apply {
                    key = "tsundoku_domain_forwarding"
                    title = requireContext().contextStringResource(TDMR.strings.domain_forwarding_title)
                    summary = requireContext().contextStringResource(TDMR.strings.domain_forwarding_plugin_summary)
                    setOnPreferenceClickListener {
                        lifecycleScope.launch {
                            val origin = runCatching { source.getCurrentBaseUrl() }.getOrDefault(source.baseUrl)
                            onDomainForwardingClick?.invoke(origin)
                        }
                        true
                    }
                },
            )
        }

        if (source is ConfigurableSource) {
            val dataStore = SharedPreferencesDataStore(source.sourcePreferences())
            preferenceManager.preferenceDataStore = dataStore

            if (source is JsSource) {
                lifecycleScope.launch {
                    source.setupPreferenceScreenAsync(sourceScreen)
                    configurePreferences(sourceScreen)
                }
            } else {
                source.setupPreferenceScreen(sourceScreen)
                configurePreferences(sourceScreen)
            }
        }

        return sourceScreen
    }

    private fun configurePreferences(screen: PreferenceScreen) {
        screen.forEach { pref ->
            pref.isIconSpaceReserved = false
            pref.isSingleLineTitle = false
            if (pref is DialogPreference && pref.dialogTitle.isNullOrEmpty()) {
                pref.dialogTitle = pref.title
            }

            if (pref is EditTextPreference) {
                val setListener = pref.getOnBindEditTextListener()
                pref.setOnBindEditTextListener {
                    setListener?.onBindEditText(it)
                    it.setIncognito(lifecycleScope)
                }
            }
        }
    }

    companion object {
        private const val SOURCE_ID = "source_id"

        fun getInstance(sourceId: Long): SourcePreferencesFragment {
            return SourcePreferencesFragment().apply {
                arguments = Bundle().apply {
                    putLong(SOURCE_ID, sourceId)
                }
            }
        }
    }
}
