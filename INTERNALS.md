## Swabbie Background

This doc presents an overview of Swabbie concepts and hopes to provide some background information on how the components work together. 
It's a good place to start if you want to add a new resource or provider to Swabbie.

### Taxonomy

**Resource**: something that will be cleaned by Swabbie

**Work Configuration**: The configuration for a distinct cloud provider + resource + regions + settings

**Account**: a cloud provider's way of grouping resources

**Namespace**: a [SwabbieNamespace](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/model/SwabbieNamespace.kt) that represents a group of ResourceType in a cloud provider/account/region.

### What does Swabbie look at?

Swabbie was built to clean up things that Spinnaker knows about. 
Some examples of those types of resources are Images, Snapshots, Autoscaling Groups, Load balancers, and Firewall Rules.
Identifying that these resources exist is easy; figuring out if they are in use is hard.

### What problem is Swabbie solving?

When you're creating resources it is easy to forget about them. 
This creates cruft in an account which costs money and slows down systems that index that account.
Swabbie cleans up unused resources.

### How do we determine "use"?

Determining use depends on the resource type.
This determination is most of the custom logic for each resource type (for example, `AmazonImageHandler`).
For lots of resources there is no cloud provided API to ask if a resource is in use. 

For one example, determining use of AMIs involves building a cache of every image used by all launch configurations and instances in every account we know about (found by asking Clouddriver). 
Each resource will have a different way to determine use.

### How do we think about the risk of deleting something that is in use?

Some cloud resources can be deleted while they're in use (like AMIs).
Some cloud resources can't be deleted while they're in use (like EBS volume snapshots: for this, the volume first has to be deleted).
It is up to the implementation to whether they'd like to do lots of expensive checking, record statistics over time, or be more lenient based on the specific resource.

### Should Swabbie work 24/7?

That's a personal choice. 
Swabbie has settings to allow it to only work on weekdays during work hours to further minimize the risk of an outage caused by something unexpectedly being deleted during off hours.

### Lifecycle of a Resource

`Mark -> (optionally) Notify -> Delete`

A resource is _marked_ when the handler has determined it is not in use.
As a resource is marked the handler also determines ownership (a DL, usually).

Right after being marked, the resource is _notified_ via the ownership information.
The notification contains an opt out link which, if clicked, will prevent a resource from ever being cleaned up.
You can configure a resource to skip notification (if no one would ever opt out or there would be too many notifications).

There's a waiting period between notifying and deleting (configurable) to ensure that an owner has time to opt out.

After this waiting period a resource is re-checked to make sure it still qualifies for deletion, then it is deleted.   

## Swabbie Internals


### Resource Handlers

[AbstractResourceTypeHandler](/swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/AbstractResourceTypeHandler.kt) The base functionality for classes that decide whether a resource is in use or not.
This class handles the generic functionality for marking/notifying/deleting/opting out of any resource.
When you add support for a new resource you shouldn't have to touch this class.

Individual resource type handlers, like [AmazonImageHandler](swabbie-aws/src/main/kotlin/com/netflix/spinnaker/swabbie/aws/images/AmazonImageHandler.kt), extend the [AbstractResourceTypeHandler](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/AbstractResourceTypeHandler.kt).

Responsibilities for resource handlers include:
  - Retrieving upstream resources.
  - Marking resources violating rules.
  - Deleting a resource.

### Work Queue

Swabbie has a work queue that contains [WorkItem](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/model/WorkConfiguration.kt)s. 
The [WorkQueueManager](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/work/WorkQueueManager.kt) is in charge of filling this queue with the appropriate items of work.
The [WorkProcessor](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/work/WorkProcessor.kt) takes work items from the queue, acquires a lock on that item, and then does the work by calling out to the handler that knows how to do the work. 

Swabbie is set up so that, with locking, you can run many instances to process this work.
Swabbie is also knowledgable about being in service/out of service.
When Swabbie is in service, or "up", it will process work.
When Swabbie is out of service, or "down", it will not process work.

### Work Configuration

A [WorkConfiguration](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/model/WorkConfiguration.kt) for each "unit of work" is built by the [WorkConfigurator](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/WorkConfigurator.kt).
This class is responsible for reading the yaml and building the correct work configuration for each [SwabbieNamespace](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/model/SwabbieNamespace.kt).
A work configuration is used by all the handlers to appropriately query for resources and perform the correct action.

### Caching 

Some things are expensive to look up (like, what AMIs are used by launch configurations), and we want to periodically build a cache of the data we need.
For example, we have several of these caches in `swabbie-aws/`.
For less memory usage we could instead store these caches in redis, but we haven't yet.

### Resource Tracking

We don't necessarily want to delete a resource the second it is not in use.
For example, we don't want to mark an image for deletion the second the last server group using it has been terminated. 
Within a reasonable time frame we might want to roll back to that image.

One example of that is the [ResourceUseTrackingRepository](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/repository/ResourceUseTrackingRepository.kt), which is used to record use and check if a resource has been used or not.


### Exclusion Policies

Resources can be excluded/opted out from consideration using exclusion policies.

- [ResourceExclusionPolicy](swabbie-core/src/main/kotlin/com/netflix/spinnaker/swabbie/exclusions/ExclusionPolicy.kt): Excludes resources at runtime


### Allowlisting

Allowlisting is part of the exclusion mechanism. When defined, only resources allowlisted will be considered, skipping everything else not allowed.
Typically we create an allowlist on the `owner` key so that we can roll out deletion of a resource to our team before the rest of the company.

### Opting Out

Any resource can be opted out of deletion, which means it will never be deleted.
We use redis to keep track of this opted out state. 
Since redis _is not_ a durable data store, we also recommend implementing a cloud provider specific tagging for all resources.
For aws, we tag each opted out resource with `expiration_time: never`.

Any resource tagged with `expiration_time: never` will never be deleted. 

### Deleting

We use orca and clouddriver to delete resources.
When we submit resources to delete to orca we use an ad-hoc pipeline with a wait stage (waiting a random amount of time) before the delete.
This helps us not exceed rate limits for deletion.

### Storage: Redis

Swabbie uses redis to keep track of locking and to store information about a resource.

Here's an example of the locking options:
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


### Notifications

When resources are marked for deletion, a notification is sent to the owner.
Resource owners are resolved using resolution strategies. Default strategy is getting the email field on the resource's application.
The deletion of a resource will be adjusted when the notification is sent, respecting the retention days for the resource type.

### Dry Run

This will ensure swabbie runs in dryRun, meaning no writes, nor any destructive action of the data will occur
```
swabbie:
  dryRun: true
```
It's also possible to turn on dryRun at a resource type level

### Testing

It's hard to test deletion for resources that must go through this long cycle of mark -> notify -> wait -> delete.
We have a [TestingController](swabbie-web/src/main/kotlin/com/netflix/spinnaker/swabbie/controllers/TestingController.kt) to help. 
This is intended for local testing only.
To configure it follow the instructions in the class.

Note: you must have a resource in mind that is safe to delete because it has to be hardcoded into the config.
This is for safety!
