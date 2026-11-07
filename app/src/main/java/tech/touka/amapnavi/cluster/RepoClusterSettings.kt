package tech.touka.amapnavi.cluster

import tech.touka.amapnavi.data.prefs.UserSettingsRepository

/**
 * 从 UserSettingsRepository 读取仪表配置。
 * 每次 get 实时读 SharedPreferences（已有内存缓存），保证设置页切换立即生效。
 */
class RepoClusterSettings(private val repo: UserSettingsRepository) : ClusterSettings {
    override val enabled: Boolean
        get() = repo.getBoolean(UserSettingsRepository.KEY_CLUSTER_ENABLE, false)
    override val tbtEnabled: Boolean
        get() = repo.getBoolean(UserSettingsRepository.KEY_CLUSTER_TBT_ENABLE, true)
    override val speedEnabled: Boolean
        get() = repo.getBoolean(UserSettingsRepository.KEY_CLUSTER_SPEED_ENABLE, true)
    override val estEnabled: Boolean
        get() = repo.getBoolean(UserSettingsRepository.KEY_CLUSTER_EST_ENABLE, true)
    override val textEnabled: Boolean
        get() = repo.getBoolean(UserSettingsRepository.KEY_CLUSTER_TEXT_ENABLE, true)
    override val tlEnabled: Boolean
        get() = repo.getBoolean(UserSettingsRepository.KEY_CLUSTER_TL_ENABLE, true)
    override val etaMode: Int
        get() = repo.getInt(UserSettingsRepository.KEY_CLUSTER_ETA_MODE, 1)
    override val experimentalIcons: Boolean
        get() = repo.getBoolean(UserSettingsRepository.KEY_CLUSTER_ICON_EXPERIMENTAL, false)
    override val adasEnabled: Boolean
        get() = repo.getBoolean(UserSettingsRepository.KEY_CLUSTER_ADA_ENABLE, false)
}
