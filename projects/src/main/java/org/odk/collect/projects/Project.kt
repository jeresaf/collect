package org.odk.collect.projects

sealed class Project {
    abstract val name: String
    abstract val icon: String
    abstract val color: String

    data class New(
        override val name: String,
        override val icon: String,
        override val color: String
    ) : Project()

    data class Saved(
        val uuid: String,
        override val name: String,
        override val icon: String,
        override val color: String
    ) : Project() {

        constructor(uuid: String, project: New) : this(
            uuid,
            project.name,
            project.icon,
            project.color
        )
    }

    companion object {
        const val PROJECT_ID = "INSIGHTS"
        const val PROJECT_NAME = "Insights"
        const val PROJECT_ICON = "I"
        const val PROJECT_COLOR = "#0d81d4"

        val PROJECT = Saved(PROJECT_ID, PROJECT_NAME, PROJECT_ICON, PROJECT_COLOR)
    }
}
