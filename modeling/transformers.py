import numpy as np
from sklearn.base import BaseEstimator
from sklearn.base import TransformerMixin


class IndexToData(BaseEstimator, TransformerMixin):
    def __init__(self, window_data, function=lambda x: x, window=20):
        self.window_data = window_data
        self.window = window
        self.function = function

    def fit(self, X=None, y=None, **fit_params):
        return self

    def transform(self, X):
        data = self.window_data[self.window]
        return [[self.function(data[Xi])] for Xi in X]


class PadTransformer(BaseEstimator, TransformerMixin):
    def __init__(self, token=0, dtype=np.long):
        self.token = token
        self.dtype = dtype

    def fit(self, X=None, y=None, **fit_params):
        return self

    def transform(self, X):
        longest = max(map(lambda x: len(x[0]), X))
        return np.array([[self.token] * (longest - len(Xi[0])) + Xi[0] for Xi in X], dtype=self.dtype)

