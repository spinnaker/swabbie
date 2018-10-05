package com.netflix.spinnaker.swabbie.model

data class SwabbieNamespace(
  val cloudProvider: String,
  val accountName: String,
  val region: String,
  val resourceType: String
) {
  companion object {
    fun namespaceParser(namespace: String): SwabbieNamespace {
      namespace.split(":").let {
        return SwabbieNamespace(it[0], it[1], it[2], it[3])
      }
    }
  }

  override fun toString(): String {
    return "$cloudProvider:$accountName:$region:$resourceType"
  }
}
