# Swabbie

_**IMPORTANT:** This service is currently under development and is not ready for production._

Swabbie is a service automating the cleanup of unused resources, such as EBS Volumes and Security Groups.
It's a replacement for Janitor Monkey. It can be extended to clean up a variety of resource types.
It applies a set of rules to mark cleanup candidates. Once marked, a resource is scheduled for deletion, and an owner is notified.

## Configuration
```
swabbie:
  agents:
    mark:
      enabled: true
      intervalSeconds: 3600000
    clean:
      enabled: false
      intervalSeconds: 3600000
    notify:
      enabled: false
      intervalSeconds: 3600000

  providers:
    - name: aws
      locations:
        - us-east-1
        - us-west-2
      accounts:
        - test
        - prod
      maxItemsProcessedPerCycle: 100
      itemsProcessedBatchSize: 25
      exclusions:
        - type: Tag
          attributes:
            - key: expiration_time
              value:
                - never
                - pattern:^\d+(d|m|y)$
      resourceTypes:
        - name: securityGroup
          enabled: false
          dryRun: true
          retention: 10 #days
          maxAge: 10 #days
          exclusions:
            - type: Literal
              attributes:
                - key: name
                  value:
                    - nf_infranstructure
                    - nf_datacenter
        - name: loadBalancer
          enabled: true
          dryRun: true
          retention: 10 #days
          maxAge: 10 #days
          entityTaggingEnabled: true
          notification:
            enabled: true
            types:
              - email
              - slack
            itemsPerMessage: 5
            defaultDestination: swabbie@spinnaker.io
            optOutBaseUrl: http://localhost:8088/
            resourceUrl: https://spinnaker/infrastructure?q=
          exclusions:
            - type: Allowlist
              attributes:
                - key: swabbieResourceOwner
                  value:
                    - user@netflix.com

```


## Concepts
#### Agents
An agent is a scheduled class in charge of initiating and dispatching work to a resource type handler:

- `ResourceMarkerAgent`: Marks violating resources.
- `ResourceCleanerAgent`: Cleans marked resources.
- `NotificationAgent`: Ensures a notification is sent out to a resource owner.


#### Resource Type Handler
Handler's lifecycle: `Mark -> Notify -> Delete`.

Responsibilities include:
  - Retrieving upstream resources.
  - Marking resources violating rules.
  - Deleting a resource.

#### Work, Work, Work...
A single unit of work is scoped to a configuration that defines its granularity.

```
data class WorkConfiguration(
  val namespace: String, // ${cloudProvider:account.name:location:resourceType}
  val account: Account,
  val location: String, // A region in aws, depends on what cloudProvider
  val cloudProvider: String,
  val resourceType: String,
  val retention: Int, // How many days swabbie will wait until deletion
  val exclusions: List<Exclusion>,
  val dryRun: Boolean = true,
  val entityTaggingEnabled: Boolean = false, //Controls
  val notificationConfiguration: NotificationConfiguration? = EmptyNotificationConfiguration(),
  val maxAge: Int = 14 // resources newer than the maxAge in days will be excluded
)
```
Work configuration is derived from the YAML configuration.

#### Marking resources for deletions & Redis
A marker agent operates on a unit of work by acquiring a simple lock to avoid operating on work in progress.
The locking mechanism is backed by a distributed redis locking manager. The granularity of the lock name is
`$action:$workConfiguration.namespace`.

```
locking:
  enabled: true
  maximumLockDurationMillis: 360000
  heartbeatRateMillis: 5000
  leaseDurationMillis: 30000
```

Scheduling the cleanup of resources is done by keeping an index of visited resources in a `ZSET`, using the projected deletion time as the `score`.
Getting elements from the `ZSET` from `-inf` to `now` will return all resources ready to be deleted.
Getting elements from the `ZSET` from `0.0` to `+inf` will return all currently marked resources.


#### Notifications
When resources are marked for deletion, a notification is sent to the owner.
Resource owners are resolved using resolution strategies. Default strategy is getting the email field on the resource's application.
The deletion of a resource will be adjusted when the notification is sent, respecting the retention days for the resource type.

#### Dry Run
This will ensure swabbie runs in dryRun, meaning no writes, nor any destructive action of the data will occur
```
swabbie:
  dryRun: true
```
It's also possible to turn on dryRun at a resource type level

#### Exclusion Policies
Resources can be excluded/opted out from consideration using exclusion policies.

- `ResourceExclusionPolicy`: Excludes resources at runtime


#### Allowlisting
Allowlisting is part of the exclusion mechanism. When defined, only resources allowlisted will be considered, skipping everything else not allowed.
