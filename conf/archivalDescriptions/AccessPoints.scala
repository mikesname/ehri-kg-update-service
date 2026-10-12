class AccessPoints {

  // The path of an item linked via an access point, relative to http://lod.ehri-project-test.eu/vocabularies/,
  // from its type, its scheme (an agent's type of entity or a concept's vocabulary) and its identifier
  def itemPath(itemType: String, scheme: String, identifier: String): String = {
    if (itemType == "CvocConcept") s"$scheme/$identifier"
    else if (scheme == "person") s"ehri-pers/$identifier"
    else if (scheme == "family") s"ehri-families/$identifier"
    else s"ehri-cb/$identifier"
  }

  def isAgentOrConcept(itemType: String): Boolean = {
    itemType == "HistoricalAgent" || itemType == "CvocConcept"
  }

}
