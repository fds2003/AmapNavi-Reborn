package tech.touka.amapnavi.cluster

enum class ClusterState {
    RELEASED,
    ACQUIRING,
    ACTIVE,
    RELEASING
}

data class ClusterStatus(
    val state: ClusterState = ClusterState.RELEASED,
    val lastError: String? = null,
    val lastSentAt: Long = 0L,
    val lastTbt: String = "",
    val lastText: String = ""
)
