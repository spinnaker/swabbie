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

## How it works
During initialization swabbie schedules work to routinely mark, notify and delete resources,
utilizing a configurable rules engine to determine which resources to mark for deletion.   

```
- operator: AND
   description: Disabled Server Groups that are older than a year
   rules:
    - name: ZeroInstanceDisabledServerGroupRule
    - name: AgeRule
      parameters:
         moreThanDays: 365
- operator: OR
   description: Expired Server Groups
   rules:
    - name: ExpiredResourceRule
```

Translates to:
`((ZeroInstanceDisabledServerGroupRule && AgeRule) || ExpiredResourceRule) == true`

Another example:
```
- operator: AND
    description: Packer Images older than 2 days
    rules:
    - name: AttributeRule
      parameters:
        description:
        - pattern:^packer-build
    - name: AgeRule
      parameters:
        moreThanDays: 2
```

Reads: `((AttributeRule && AgeRule) == true`
Rules that are defined with `AND` operator will apply only if all rules apply, similar to the logical `&&` operator.
`OR` rules apply only if any rule apply similar to the logical `||` operator.

To enable rules for a resource type:
```
resourceTypes:
  - name: image
    enabled: true
    enabledRules:
    - operator: AND
        description: Packer Images older than 2 days
        rules:
        - name: AttributeRule
          parameters:
            description:
            - pattern:^packer-build
        - name: AgeRule
          parameters:
            moreThanDays: 2
```

## Running swabbie
Requirements: 
- Redis for storage
- Application YAML [Configuration](swabbie-core/src/resources/swabbie.yml) (maps to [SwabbieProperties](swabbie-core/src/main/kotlin/com/netflix/spinnaker/config/SwabbieProperties.kt))

./gradlew run


