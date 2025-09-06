package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import app.lawnchair.data.folder.model.FolderOrderUtils
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.flowerpot.Flowerpot
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.R
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.AppInfoComparator
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.allapps.WorkProfileManager
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.ActivityContext
import com.patrykmichalik.opto.core.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.function.Predicate

@Suppress("SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN")
class LawnchairAlphabeticalAppsList<T>(
    private val context: T,
    private val appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
) : AlphabeticalAppsList<T>(context, appsStore, workProfileManager, privateProfileManager)
    where T : Context, T : ActivityContext {

    private var hiddenApps: Set<String> = setOf()
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val prefs = PreferenceManager.getInstance(context)

    private val viewModel: FolderViewModel by (context as ComponentActivity).viewModels()
    private var folderList = mutableListOf<FolderInfo>()

    private val folderOrder = FolderOrderUtils.stringToIntList(prefs.drawerListOrder.get())
    private val potsManager = Flowerpot.Manager.getInstance(context)

    private var workJob: Job? = null

    init {
        context.launcher.deviceProfile.inv.addOnChangeListener { onAppsUpdated() }
        try {
            prefs2.hiddenApps.onEach(launchIn = context.launcher.lifecycleScope) {
                hiddenApps = it
                onAppsUpdated()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize hidden apps", t)
        }
        observeFolders()
    }

    private fun observeFolders() {
        viewModel.foldersLiveData.observe(context as LifecycleOwner) { folders ->
            folderList = folders
                .sortedBy { folderOrder.indexOf(it.id) }
                .toMutableList()
            onAppsUpdated()
        }
    }

    override fun updateItemFilter(itemFilter: Predicate<ItemInfo>?) {
        mItemFilter = Predicate { info ->
            require(info is AppInfo) { "`info` must be an instance of `AppInfo`." }
            val componentKey = info.toComponentKey().toString()
            (itemFilter?.test(info) != false) && !hiddenApps.contains(componentKey)
        }
        onAppsUpdated()
    }

    override fun onAppsUpdated() {
        workJob?.cancel()
        workJob = (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Default) {
            if (mAllAppsStore == null) return@launch
            if (hasSearchResults()) {
                withContext(Dispatchers.Main) {
                    super.onAppsUpdated()
                }
                return@launch
            }

            val newAdapterItems = ArrayList<AdapterItem>()
            val newFastScrollerSections = ArrayList<FastScrollSectionInfo>()

            val appComparator = AppInfoComparator(context)
            val filteredApps = mAllAppsStore.apps
                .asSequence()
                .filter { mItemFilter?.test(it) ?: true }
                .sortedWith(appComparator)
                .toList()

            var position = 0
            if (mWorkManager != null) {
                position += mWorkManager.addWorkItems(newAdapterItems)
                if (mWorkManager.shouldShowWorkApps()) {
                    if (position == 1) {
                        newFastScrollerSections.add(
                            FastScrollSectionInfo(
                                context.resources.getString(R.string.work_profile_edu_section), 0
                            )
                        )
                    }
                    position = addAppsWithSections(filteredApps, position, newAdapterItems, newFastScrollerSections)
                }
            } else {
                position = addAppsWithSections(filteredApps, position, newAdapterItems, newFastScrollerSections)
            }
            if (mPrivateProfileManager != null) {
                addPrivateSpaceItems(position, newAdapterItems, newFastScrollerSections)
            }

            val newAccessibilityResultsCount = newAdapterItems.count { it.isCountedForAccessibility }
            var newNumAppRowsInAdapter = 0
            if (numAppsPerRow != 0) {
                var numAppsInSection = 0
                var numAppsInRow = 0
                var rowIndex = -1
                for (item in newAdapterItems) {
                    item.rowIndex = 0
                    if (BaseAllAppsAdapter.isDividerViewType(item.viewType) ||
                        BaseAllAppsAdapter.isPrivateSpaceHeaderView(item.viewType) ||
                        BaseAllAppsAdapter.isPrivateSpaceSysAppsDividerView(item.viewType)) {
                        numAppsInSection = 0
                    } else if (BaseAllAppsAdapter.isIconViewType(item.viewType)) {
                        if (numAppsInSection % numAppsPerRow == 0) {
                            numAppsInRow = 0
                            rowIndex++
                        }
                        item.rowIndex = rowIndex
                        item.rowAppIndex = numAppsInRow
                        numAppsInSection++
                        numAppsInRow++
                    }
                }
                newNumAppRowsInAdapter = rowIndex + 1
            }

            withContext(Dispatchers.Main) {
                val diffResult = DiffUtil.calculateDiff(MyDiffCallback(mAdapterItems, newAdapterItems))
                mAdapterItems.clear()
                mAdapterItems.addAll(newAdapterItems)
                mFastScrollerSections.clear()
                mFastScrollerSections.addAll(newFastScrollerSections)

                try {
                    val accessibilityResultsCountField = AlphabeticalAppsList::class.java.getDeclaredField("mAccessibilityResultsCount")
                    accessibilityResultsCountField.isAccessible = true
                    accessibilityResultsCountField.set(this@LawnchairAlphabeticalAppsList, newAccessibilityResultsCount)

                    val numAppRowsInAdapterField = AlphabeticalAppsList::class.java.getDeclaredField("mNumAppRowsInAdapter")
                    numAppRowsInAdapterField.isAccessible = true
                    numAppRowsInAdapterField.set(this@LawnchairAlphabeticalAppsList, newNumAppRowsInAdapter)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set private fields", e)
                }

                diffResult.dispatchUpdatesTo(mAdapter)
            }
        }
    }

    private fun addAppsWithSections(
        appList: List<AppInfo>,
        startPosition: Int,
        adapterItems: MutableList<AdapterItem>,
        fastScrollerSections: MutableList<FastScrollSectionInfo>
    ): Int {
        var position = startPosition
        if (prefs.drawerList.get()) {
            val folderApps = mutableSetOf<AppInfo>()
            folderList.forEach { folder ->
                if (folder.contents.size > 1) {
                    val folderInfo = FolderInfo()
                    folderInfo.title = folder.title
                    adapterItems.add(AdapterItem.asFolder(folderInfo))
                    folder.contents.forEach { app ->
                        (appsStore.getApp(app.componentKey) as? AppInfo)?.let {
                            folderInfo.add(it)
                            if (prefs.folderApps.get()) folderApps.add(it)
                        }
                    }
                }
                position++
            }
            val remainingApps = appList.filterNot { app -> folderApps.contains(app) }
            position = addAppsWithSectionsInternal(remainingApps, position, adapterItems, fastScrollerSections)
        } else {
            val categorizedApps = potsManager.categorizeApps(appList)
            categorizedApps.forEach { (category, apps) ->
                if (apps.size == 1) {
                    adapterItems.add(AdapterItem.asApp(apps.first()))
                } else {
                    val folderInfo = FolderInfo().apply {
                        title = category
                        apps.forEach { add(it) }
                    }
                    adapterItems.add(AdapterItem.asFolder(folderInfo))
                }
                position++
            }
        }
        return position
    }

    private fun addAppsWithSectionsInternal(
        appList: List<AppInfo>,
        startPosition: Int,
        adapterItems: MutableList<AdapterItem>,
        fastScrollerSections: MutableList<FastScrollSectionInfo>
    ): Int {
        var lastSectionName: String? = null
        var position = startPosition
        for (info in appList) {
            adapterItems.add(AdapterItem.asApp(info))
            val sectionName = info.sectionName
            if (sectionName != lastSectionName) {
                lastSectionName = sectionName
                fastScrollerSections.add(FastScrollSectionInfo(sectionName, position))
            }
            position++
        }
        return position
    }

    private fun addPrivateSpaceItems(
        startPosition: Int,
        adapterItems: MutableList<AdapterItem>,
        fastScrollerSections: MutableList<FastScrollSectionInfo>
    ) {
        if (mPrivateProfileManager != null
            && !mPrivateProfileManager.isPrivateSpaceHidden
            && mPrivateProfileManager.apps.isNotEmpty()
        ) {
            var position = mPrivateProfileManager.addPrivateSpaceHeader(adapterItems)
            fastScrollerSections.add(
                FastScrollSectionInfo(
                    mPrivateProfileManager.privateProfileAppScrollerBadge, position
                )
            )
            if (mPrivateProfileManager.currentState == PrivateProfileManager.STATE_ENABLED) {
                addAppsWithSectionsInternal(mPrivateProfileManager.apps, position, adapterItems, fastScrollerSections)
            }
        }
    }
}
