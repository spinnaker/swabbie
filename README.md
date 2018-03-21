# Swabbie

_**IMPORTANT:** This service is currently under development and is not ready for production._

Swabbie is a service automating the cleanup of unused resources, such as EBS Volumes and Security Groups.
It's a replacement for Janitor Monkey. It can be exetnded to clean up a variety of resource types.
It applies a set of rules to mark cleanup candidates. Once marked, a resource is scheduled for deletion, and an owner is notified.

## Configuration
```
swabbie:
  taggingEnabled: false
  optOut:
    url: http://localhost:8088/
    defaultTtlDays: 14

  agents:
    mark:
      enabled: true
      intervalSeconds: 3600000
    clean:
      enabled: true
      intervalSeconds: 3600000
    notify:
      enabled: false
      intervalSeconds: 3600000
      fallbackEmail: cloudmonkey@netflix.com

  providers:
    - name: aws
      locations:
        - us-east-1
      accounts:
        - test

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
          retentionDays: 10
          exclusions:
            - type: Name
              attributes:
                - key: name
                  value:
                    - nf_infranstructure
                    - nf_datacenter
        - name: loadBalancer
          enabled: true
          dryRun: true
          retentionDays: 14

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
  val namespace: String,
  val account: Account,
  val location: String,
  val cloudProvider: String,
  val resourceType: String,
  val retentionDays: Int,
  val exclusions: List<Exclusion>,
  val dryRun: Boolean = true
)
```
Work configuration is derived from the YAML configuration.

#### Marking resources for deletions & Redis
A marker agent operates on a unit of work by acquiring a simple lock to avoid operating on work in progress.
The locking mechanism is backed by `Redis`, a `SETNX ` with a `TTL` using a key with this granularity: `$agentName:$WorkConfiguration.namespace`

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
- TODO
