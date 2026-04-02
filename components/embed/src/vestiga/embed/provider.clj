(ns vestiga.embed.provider)

(defprotocol EmbeddingProvider
  (embed-texts [this texts]
    "Embed a batch of texts. Returns a vector of float arrays.
     Each float array has dimension = (embedding-dim this).
     texts: vector of strings
     Returns: vector of float[] (Java float arrays)")

  (embedding-dim [this]
    "Return the dimensionality of the embeddings produced.")

  (provider-name [this]
    "Return a string identifying this provider, for logging."))
