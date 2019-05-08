import numpy as np
from sklearn.base import BaseEstimator
from sklearn.base import TransformerMixin


class IndexToData(BaseEstimator, TransformerMixin):
    """
    given a dataset as a hyperparameter, the transform converts indices to datapoints
    """
    def __init__(self, window_data, function=lambda x: x, window=20):
        self.window_data = window_data
        self.window = window
        self.function = function

    def fit(self, X=None, y=None, **fit_params):
        return self

    def transform(self, X):
        data = self.window_data[self.window]
        return [self.function(data[Xi[0]]) for Xi in X]


class PadTransformer(BaseEstimator, TransformerMixin):
    """
    pad the dataset X, in the form of a list, to the length of the longest sequence in the list
    each batch may have a different size, the length is not fixed as a a hyperparam
    """
    def __init__(self, token=0, dtype=np.long, pad_side='left'):
        self.token = token
        self.dtype = dtype
        self.pad_side = pad_side

    def fit(self, X=None, y=None, **fit_params):
        return self

    def transform(self, X):
        longest = max(map(lambda x: len(x), X))
        if self.pad_side == 'left':
            return np.array([[self.token] * (longest - len(Xi)) + Xi for Xi in X], dtype=self.dtype)
        elif self.pad_side == 'right':
            return np.array([Xi + [self.token] * (longest - len(Xi)) for Xi in X], dtype=self.dtype)


class SequenceSelector(BaseEstimator, TransformerMixin):
    """
    Select a given sequence as a column member, given an index column member
    """

    def __init__(self, data, key=None, window_size=None):
        self.key = key
        self.data = data
        self.window_size = window_size

    def fit(self, x, y=None):
        return self

    def transform(self, indices):
        return [self.data[self.window_size][idx[0]][self.key] for idx in indices]


class OperatorSelector(BaseEstimator, TransformerMixin):
    """
    Select the operator as a column member, given an index column member
    This operator does not need to select between operators, so any copy of the dataset is passed in
    There's no need to make another copy in memory of the operators, just index in
    """

    def __init__(self, data):
        self.data = data

    def fit(self, x, y=None):
        return self

    def transform(self, indices):
        return [[self.data[idx[0]]['operator']] for idx in indices]


class DenseTransformer(TransformerMixin):
    """
    convert a sparse array to dense. Needed for dense steps like PCA
    """
    def fit(self, X, y=None, **fit_params):
        return self

    def transform(self, X, y=None, **fit_params):
        return X.todense()
