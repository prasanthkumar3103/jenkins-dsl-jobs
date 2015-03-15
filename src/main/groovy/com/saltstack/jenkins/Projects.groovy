package com.saltstack.jenkins

import com.saltstack.jenkins.projects.*

class Projects {

    static def get_projects() {
        return [
            new Bootstrap(),
            new LibNACL(),
            new Raet(),
            new Salt(),
            new Sorbic()
        ]
    }

    def setup_projects_webhooks(manager) {
        get_projects().each { project ->
            project.configureBranchesWebHooks(manager)
            project.configurePullRequestsWebHooks(manager)
        }
    }
}
