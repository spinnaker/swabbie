package com.netflix.spinnaker.swabbie.model

interface ResourceResponse

data class Tasks(var taskIds: List<String> = mutableListOf()) : ResourceResponse
