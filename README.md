# Swabbie

_**IMPORTANT:** This service is currently under development and is not ready for production._

Swabbie is a service automating the cleanup of unused resources, such as EBS Volumes and Security Groups.
It's a replacement for Janitor Monkey.
It applies a set of rules to mark cleanup candidates. Once marked, a resource is scheduled for deletion, and an owner is notified.

## Configuration
```
swabbie:
  dryRun: true
  taggingEnabled: false
  optOut:
    url: http://localhost:8088/
    defaultTtlDays: 14

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

      exclusions:
        - type: Tag
          attributes:
            - key: expiration_time
              value:
                - never
                - pattern:^\d+(d|m|y)$

        - type: AccountName
          attributes:
            - key: name
              value:
                - importantAccount

      resourceTypes:
        - name: securityGroup
          enabled: true
          retentionDays: 10
          exclusions:
            - type: Name
              attributes:
                - key: name
                  value:
                    - sg_to_exclude

```


## Concepts
#### Agents
A `SwabbieAgent` is a scheduled class in charge of initiating and dispatching work to a resource handler:

- `ResourceMarkerAgent`: Marks violating resources.
- `ResourceCleanerAgent`: Cleans marked resources.
- `NotificationAgent`: Ensures a notification is sent out to a resource owner.


#### Resource Handler
Handler's lifecycle: `Mark -> Notify -> Delete`.

Responsibilities include:
  - Retrieving upstream resources.
  - Marking resources violating rules.
  - Deleting a resource.

#### Work, Work, Work...
A single unit of work is scoped to a configuration that defines its granularity.

```
data class Work(
  val namespace: String,
  val account: Account,
  val location: String,
  val cloudProvider: String,
  val resourceType: String,
  val retentionDays: Int = 14,
  val exclusions: List<Exclusion>,
  val dryRun: Boolean = true
)
```
Work configuration is derived from the YAML configuration.

#### Marking & Redis
A marker agent operates on a unit of work by acquiring a simple lock to avoid operating on work in progress.
The locking mechanism is backed by `Redis`, a `SETNX ` with a `TTL`.
Scheduling the cleanup of resources is done by keeping an index in a `ZSET` using the projected deletion time as the `score`.
This takes advantage of Redis Sorted Sets.

#### Deleting & Redis
Getting elements from the `ZSET` from `-inf` to `now` and delete them.


#### Notifications
When resources are marked for deletion, a notification is sent to the owner.
Resource owners are resolved using resolution strategies. Default strategy is getting the email field on the resource's application.

#### Dry Run
TODO

#### Exclusion Policies
Swabbie includes all resources defined in the configuration by default.
Resources can be excluded/opted out from consideration using exclusion policies.


There are two types of Exclusion Policies:

- `WorkConfigurationExclusionPolicy`: Excludes work at configuration time
- `ResourceExclusionPolicy`: Excludes resources at runtime
