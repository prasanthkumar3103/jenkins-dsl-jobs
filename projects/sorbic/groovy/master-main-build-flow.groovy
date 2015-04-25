import hudson.FilePath

// Let's get the day of the week since converage reports should only run on Sunday's
def today_is_sunday = new Date()[Calendar.DAY_OF_WEEK] == Calendar.SUNDAY

guard {
    retry(3) {
        clone = build('sorbic/master/clone')
    }

    // Let's run Lint & Unit in parallel
    parallel (
        {
            lint = build('sorbic/master/lint',
                         CLONE_BUILD_ID: clone.build.number,
                         RUN_COVERAGE: today_is_sunday)
        },
        {
            unit = build('sorbic/master/unit',
                         CLONE_BUILD_ID: clone.build.number)
        }
    )

} rescue {

    // Let's instantiate the build flow toolbox
    def toolbox = extension.'build-flow-toolbox'

    local_lint_workspace_copy = build.workspace.child('lint')
    local_lint_workspace_copy.mkdirs()
    toolbox.copyFiles(lint.workspace, local_lint_workspace_copy)

    local_unit_workspace_copy = build.workspace.child('unit')
    local_unit_workspace_copy.mkdirs()
    toolbox.copyFiles(unit.workspace, local_unit_workspace_copy)

    /*
     *  Copy the clone build changelog.xml into this jobs root for proper changelog report
     *  This does not currently work but is here for future reference
     */
     def clone_changelog = new FilePath(clone.getRootDir()).child('changelog.xml')
     def build_changelog = new FilePath(build.getRootDir()).child('changelog.xml')
     build_changelog.copyFrom(clone_changelog)
}
