# Swabbie

Proposal doc: https://docs.google.com/document/d/1XZ_g9sPc-UE8JrTARnSjWvpSvFiZ1oJTFUbXQJqB5B0/edit#

Swabbie is a service automating the cleanup of unused resources such as EBS Volumes and Security Groups.
It’s a replacement for Janitor Monkey currently used internally at Netflix. 
It applies a set of rules to mark cleanup candidates. Once marked, a resource is scheduled for deletion, and an owner is notified

## Concepts

Swabbie is consisted of `SwabbieAgents`, scheduled classes configured to do carry on Swabbie's actions.
- ResourceMarkerAgent: Marks violating resources
- ResourceCleanerAgent: Cleans marked resources
- NotificationAgent: Ensures a notification is sent out to a resource owner

Each agent dispatches actual work to a specific handler that is resource type aware.

Resource Handler's responsibility include:
  - Retrieving upstream resources
  - Marking a violating resource
  - Deleting a violating resource

The work of a handler is scoped to a configuration containing details about the action to be taken.
A scope of work configuration contains the granularity for a given action:

```
data class ScopeOfWorkConfiguration(
  val namespace: String,
  val account: Account,
  val location: String,
  val cloudProvider: String,
  val resourceType: String,
  val retention: Retention,
  val exclusions: List<Exclusion>,
  val dryRun: Boolean = true
)
```

