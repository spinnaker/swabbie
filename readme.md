# Swabbie

Proposal doc: https://docs.google.com/document/d/1XZ_g9sPc-UE8JrTARnSjWvpSvFiZ1oJTFUbXQJqB5B0/edit#

Swabbie is a service automating the cleanup of unused resources, such as EBS Volumes and Security Groups.
It's a replacement for Janitor Monkey.
It applies a set of rules to mark cleanup candidates. Once marked, a resource is scheduled for deletion, and an owner is notified.

## Configuration
```
swabbie:
  enabled: true
  mark:
    enabled: true
    intervalSeconds: 120000

  clean:
    enabled: true
    intervalSeconds: 120000

  dryRun: true
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
                - pattern/^\d+(d|m|y)$

        - type: Account
          attributes:
            - key: account
              value:
                - test

      resourceTypes:
        - name: securityGroup
          enabled: true
          dryRun: true
          retention:
            days: 10
            ageThresholdDays: 10
          exclusions:
            - type: Simple
              attributes:
                - key: name
                  value:
                    - nf-datacenter
                    - nf-infrastructure
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
The scope of work configuration is derived from the YAML configuration.

#### Marking & Redis
A marker agent operates on a unit of work by acquiring a simple lock to avoid operating on work in progress.
The locking mechanism is backed by `Redis`, a `SETNX ` with a `TTL`.
Scheduling the cleanup of resources is done by keeping an index in a `ZSET` using the projected deletion time as the `score`. 
This takes advantage of Redis Sorted Sets.

#### Deleting & Redis
Getting elements from the `ZSET` from `inf` to `now` and delete them.


#### Dry Run
TODO

#### Excluding resources
Swabbie includes all resources defined in the configuration by default. Exclusion rules can be used to exclude resources matching certain criteria.
