# Swabbie

_**IMPORTANT:** This service is currently under development, and is actively being used at Netflix for deleting images and snapshots of ebs volumes._

_**NOTE:** If you're interested in contributing support for other providers or resource types, open an issue or join the Spinnaker Team slack and post in #swabbie._

Swabbie automates the cleanup of unused resources such as EBS Volumes and Images.
As a Janitor Monkey replacement, it can also be extended to clean up a variety of resource types.
Swabbie applies a set of rules to mark cleanup candidates. 
Once marked, a resource is scheduled for deletion, and an owner is notified.
Before being deleted the resource is checked again to make sure it still qualifies for deletion.
If so, it is deleted.

## Deep Dive

For a more detailed understanding of how Swabbie works, visit [the internals doc](INTERNALS.md).

## Configuration

There are lots of configuration options. 
Configuration can be applied at the top level, at the provider level, or a the resource type level. 

#### Top Level Config

Top level config is located under the `swabbie` key.
Examples of top level properties are:

* `dryRun`
* `work.intervalMs`
* `queue.monitorIntervalMs`
* `schedule`

These map to [SwabbieProperties](swabbie-core/src/main/kotlin/com/netflix/spinnaker/config/SwabbieProperties.kt).

#### Provider Level Config  

Provider level config options control specifics about the overall provider config.
Some examples are:

* `locations`
* `maxItemsProcessedPerCycle`
* `itemsProcessedBatchSize`
* `exclusions`

You can configure exclusions for the whole provider in this sections. 
These exclusions add to exclusions defined for each resource type.

These map to [CloudProviderConfiguration](swabbie-core/src/main/kotlin/com/netflix/spinnaker/config/SwabbieProperties.kt).

#### Resource Type Level Config  

Resource type level config options are for behavior of the specific resource type.
Some examples are:

* `dryRun`
* `maxAge`
* `exclusions`
* `notification`

Any exclusions defined here apply for only the specific resource type and add to any exclusions defined at the provider level.

These map to [ResourceTypeConfiguration](swabbie-core/src/main/kotlin/com/netflix/spinnaker/config/SwabbieProperties.kt).

### Example

Here is an example of a configuration for Swabbie. 
For current values and structure it is best to look at [SwabbieProperties](swabbie-core/src/main/kotlin/com/netflix/spinnaker/config/SwabbieProperties.kt).

```
swabbie:
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
        - name: image
          enabled: true
          dryRun: false
          retention: 2
          maxAge: 30
          exclusions:
            - type: Literal
              attributes:
                - key: description
                  value:
                    - pattern:name=base
          notification:
            enabled: true
            required: true
            types:
              - EMAIL
            defaultDestination: swabbie-email-notifications@
            optOutBaseUrl: apiUrl
            resourceUrl: helpfulUrl
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

