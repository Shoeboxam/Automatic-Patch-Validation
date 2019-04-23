# import numpy as np
# from scipy.spatial.distance import euclidean
#
# from fastdtw import fastdtw
#
# # x = np.array([[1,1], [2,2], [3,3], [4,4], [5,5]])
# # y = np.array([[2,2], [3,3], [4,4]])
#
# x = np.array([37,9,9,9,40,32,2,53,26,49,26,49,2,53])
# y = np.array([9,9,40,33,9,32,2,49,2,11,26,49,9,2,17,11,9,34,9,9,40])
# distance, path = fastdtw(x, y, dist=euclidean)
# print(distance)

import numpy as np

from seqlearn.hmm import MultinomialHMM


text = [w.split() for w in ["this DT",
                            "is V",
                            "a DT",
                            "test N",
                            "for IN",
                            "a DT",
                            "hidden Adj",
                            "Markov N",
                            "model N"]]
words, y = zip(*text)
lengths = [len(text)]

vocab, identities = np.unique(words, return_inverse=True)
X = (identities.reshape(-1, 1) == np.arange(len(vocab))).astype(int)

clf = MultinomialHMM()
clf.fit(X, y, lengths)

print(clf.predict(X))