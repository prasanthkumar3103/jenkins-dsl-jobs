import hudson.FilePath

guard {
    retry(3) {
        clone = build('salt-testing/develop/clone')
    }

    // Let's run Lint & Unit in parallel
    parallel (
        {
            lint = build('salt-testing/develop/lint',
                         CLONE_BUILD_ID: clone.build.number)
        },
    )

} rescue {

    // Let's instantiate the build flow toolbox
    def toolbox = extension.'build-flow-toolbox'

    local_lint_workspace_copy = build.workspace.child('lint')
    local_lint_workspace_copy.mkdirs()
    toolbox.copyFiles(lint.workspace, local_lint_workspace_copy)

    /*
     *  Copy the clone build changelog.xml into this jobs root for proper changelog report
     *  This does not currently work but is here for future reference
     */
     def clone_changelog = new FilePath(clone.getRootDir()).child('changelog.xml')
     def build_changelog = new FilePath(build.getRootDir()).child('changelog.xml')
     build_changelog.copyFrom(clone_changelog)
}
