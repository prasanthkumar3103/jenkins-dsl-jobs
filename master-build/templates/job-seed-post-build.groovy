import com.saltstack.jenkins.Projects

def projects = new Projects()
projects.setup_projects_webhooks(manager)
