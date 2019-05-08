import numpy as np

# models
from sklearn.decomposition import PCA
from sklearn.naive_bayes import MultinomialNB
from sklearn.tree import DecisionTreeClassifier
from sklearn.svm import SVC
from sklearn.linear_model import LogisticRegression

# feature engineering
from imblearn.pipeline import Pipeline
from sklearn.pipeline import FeatureUnion
from imblearn.over_sampling import RandomOverSampler

from sklearn.feature_extraction.text import TfidfVectorizer, CountVectorizer
from sklearn.preprocessing import OneHotEncoder

# feature Engineering- custom
from .transformers import \
    IndexToData, \
    PadTransformer, \
    OperatorSelector, \
    DenseTransformer, \
    SequenceSelector

# preprocessors
from modeling.preprocess import get_vocabulary, data_generator

WINDOW_SIZES = [11, 21, 51]
window_data = {size: list(data_generator(size)) for size in WINDOW_SIZES}


# indices have a fixed size, which makes them compatible with the tight datatype restrictions in sklearn.
# a deindexing step occurs before padding or within a custom model
def index_datasource():
    return [
        [[i] for i in range(len(window_data[WINDOW_SIZES[0]]))],
        list(map(lambda x: 1 if x['label'] == 'CORRECT' else 0, window_data[WINDOW_SIZES[0]]))
    ]


model_specifications = [
    {
        "name": "Multinomial Naive Bayes TF IDF",
        "class": Pipeline,
        "args": [[
            ('sampler', RandomOverSampler()),
            ('selector', SequenceSelector(data=window_data)),
            ('tfIdfVectorizer', TfidfVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x)),
            ('naiveBayes', MultinomialNB())
        ]],
        "datasource": index_datasource,
        "hyperparameters": {
            "selector__key": ['buggy', 'fixed'],
            "selector__window_size": WINDOW_SIZES,
            "naiveBayes__class_prior": [[.3, .7]]  # [[*reversed(get_class_priors())]]
        }
    },
    {
        "name": "Multinomial Naive Bayes Counts",
        "class": Pipeline,
        "args": [[
            ('sampler', RandomOverSampler()),
            ('selector', SequenceSelector(data=window_data)),
            ('tfIdfVectorizer', CountVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x)),
            ('naiveBayes', MultinomialNB())
        ]],
        "datasource": index_datasource,
        "hyperparameters": {
            "selector__key": ['buggy', 'fixed'],
            "selector__window_size": WINDOW_SIZES,
            "naiveBayes__class_prior": [[.3, .7]]  # [[*reversed(get_class_priors())]]
        }
    },
    {
        "name": "Decision Tree",
        "class": Pipeline,
        "args": [[
            ('sampler', RandomOverSampler()),
            ('selector', SequenceSelector(data=window_data)),
            ('tfIdfVectorizer', TfidfVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x)),
            ('naiveBayes', DecisionTreeClassifier())
        ]],
        "datasource": index_datasource,
        "hyperparameters": {
            "selector__key": ['buggy', 'fixed'],
            "selector__window_size": WINDOW_SIZES
        }
    },
    {
        "name": "Support Vector Classifier",
        "class": Pipeline,
        "args": [[
            ('sampler', RandomOverSampler()),
            ('selector', SequenceSelector(data=window_data)),
            ('tfIdfVectorizer', TfidfVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x)),
            ('naiveBayes', SVC())
        ]],
        "datasource": index_datasource,
        "hyperparameters": {
            "selector__key": ['buggy', 'fixed'],
            "selector__window_size": WINDOW_SIZES
        }
    },
    {
        "name": "Logistic Regression Operator",
        "class": Pipeline,
        "args": [[
            ('sampler', RandomOverSampler()),
            ('union', FeatureUnion(
                transformer_list=[
                    ('bytecodes', Pipeline([
                        ('selector', SequenceSelector(data=window_data, key='buggy')),
                        ('tfIdfVectorizer', TfidfVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x)),
                        # ('toDense', DenseTransformer()),
                        # ('canonicalCorrelation', PCA(n_components=5))
                    ])),
                    ('operation', Pipeline([
                        ('selector', OperatorSelector(data=window_data[WINDOW_SIZES[0]])),
                        ('oneHot', OneHotEncoder())
                    ]))
                ]
            )),
            ('logisticRegression', LogisticRegression())
        ]],
        "datasource": index_datasource,
        "hyperparameters": {
            "union__bytecodes__selector__window_size": WINDOW_SIZES,  # maximum window size to collect bytecodes
        }
    },
    {
        "name": "Logistic Regression PCA",
        "class": Pipeline,
        "args": [[
            ('sampler', RandomOverSampler()),
            ('selector', SequenceSelector(data=window_data, key='buggy')),
            ('tfIdfVectorizer', TfidfVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x)),
            ('to_dense', DenseTransformer()),
            ('principalComponents', PCA(n_components=10)),
            ('logisticRegression', LogisticRegression())
        ]],
        "datasource": index_datasource,
        "hyperparameters": {
            'principalComponents__n_components': [10, 15, 30],
            "selector__window_size": WINDOW_SIZES  # maximum window size to collect bytecodes
        }
    },
    {
        "name": "Logistic Regression",
        "class": Pipeline,
        "args": [[
            ('sampler', RandomOverSampler()),
            ('selector', SequenceSelector(data=window_data, key='buggy')),
            ('tfIdfVectorizer', TfidfVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x)),
            ('logisticRegression', LogisticRegression())
        ]],
        "datasource": index_datasource,
        "hyperparameters": {
            "selector__window_size": WINDOW_SIZES  # maximum window size to collect bytecodes
        }
    }
]


# torch models
try:
    from .models.skorch_recurrent.network import RecurrentClassifier
    from modeling.models.skorch_recurrent_operator.network import RecurrentClassifierOperator

    from skorch import NeuralNetClassifier
    import torch

    model_specifications.extend([
        {
            "name": "TorchRecurrent",
            "class": Pipeline,
            "datasource": index_datasource,
            "args": [[
                ('sampler', RandomOverSampler()),
                ('deindexer', IndexToData(window_data)),
                ('pad2d', PadTransformer(token=0, dtype=np.long, pad_side='left')),
                ('model', NeuralNetClassifier(
                    module=RecurrentClassifier,
                    criterion=torch.nn.CrossEntropyLoss,
                    optimizer=torch.optim.SGD,
                    batch_size=10,
                    max_epochs=15,
                    module__output_size=2,
                    module__vocabulary_size=len(get_vocabulary())))
            ]],
            "hyperparameters": {
                "deindexer__window": WINDOW_SIZES,  # maximum window size to collect bytecodes
                'deindexer__function': [lambda x: x['buggy'], lambda x: x['fixed']],

                # [step name]__[skorch object]__[hyperparam axis]
                "model__module__layer_type": [torch.nn.RNN, torch.nn.LSTM],
                "model__module__input_size": [25],
                "model__module__recurrent_hidden_dim": [5],  # dimensionality of the hidden LSTM layers
                "model__module__recurrent_layers": [1, 4],  # number of LSTM layers
                "model__optimizer__lr": [0.1, 0.5],
            }
        },
        {
            "name": "TorchRecurrentLabeled",
            "class": Pipeline,
            "datasource": index_datasource,
            "args": [[
                ('sampler', RandomOverSampler()),
                ('model', NeuralNetClassifier(
                    module=RecurrentClassifierOperator,
                    criterion=torch.nn.CrossEntropyLoss,
                    optimizer=torch.optim.SGD,
                    batch_size=10,
                    max_epochs=15,
                    module__vocabulary_size=len(get_vocabulary()),
                    module__operators_size=3))
            ]],
            "hyperparameters": {
                "model__module__window_size": WINDOW_SIZES,  # maximum window size to collect bytecodes
                'model__module__data': [window_data],

                # [step name]__[skorch object]__[hyperparam axis]
                "model__module__layer_type": [torch.nn.LSTM],  # torch.nn.RNN
                "model__module__input_size": [10],
                "model__module__recurrent_hidden_dim": [5],  # dimensionality of the hidden LSTM layers
                "model__module__recurrent_layers": [1, 4],  # number of LSTM layers
                "model__optimizer__lr": [0.1, 0.5],
            }
        }
    ])
except ImportError as err:
    print('PyTorch models were not loaded because of a missing dependency:', err)

try:
    from .models.hidden_markov_model.model import MultinomialHMM

    model_specifications.extend([
        {
            "name": "Multinomial Hidden Markov Model",
            "class": Pipeline,
            "args": [[
                ('sampler', RandomOverSampler()),
                ('selector', SequenceSelector(data=window_data)),
                ('pad2d', PadTransformer(token=0, dtype=np.long, pad_side='right')),
                ('naiveBayes', MultinomialNB())
            ]],
            "datasource": index_datasource,
            "hyperparameters": {
                "selector__key": ['buggy', 'fixed'],
                "selector__window_size": WINDOW_SIZES
            }
        }
    ])
except ImportError:
    print('Multinomial hidden markov model is not loaded because seqlearn is not installed.')
