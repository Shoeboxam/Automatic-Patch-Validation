from modeling.preprocess import \
    mutate_tf_idf, mutate_counts, \
    get_class_priors, get_vocabulary, \
    split_buggy, data_generator

import numpy as np
from sklearn.naive_bayes import MultinomialNB
from sklearn.tree import DecisionTreeClassifier
from sklearn.svm import SVC

from imblearn.pipeline import Pipeline
from imblearn.over_sampling import RandomOverSampler

from .transformers import IndexToData, PadTransformer


model_specifications = [
    {
        "name": "Multinomial Naive Bayes TF IDF",
        "class": MultinomialNB,
        "datasource": lambda: mutate_tf_idf(split_buggy(data_generator())),
        "hyperparameters": {
            "class_prior": [[.3, .7]]  # [[*reversed(get_class_priors())]]
        }
    },
    {
        "name": "Multinomial Naive Bayes Counts",
        "class": MultinomialNB,
        "datasource": lambda: mutate_counts(split_buggy(data_generator())),
        "hyperparameters": {
            "class_prior": [[*reversed(get_class_priors())]]
        }
    },
    {
        "name": "Decision Tree",
        "class": DecisionTreeClassifier,
        "datasource": lambda: mutate_tf_idf(split_buggy(data_generator())),
        "hyperparameters": {}
    },
    {
        "name": "Support Vector Classifier",
        "class": SVC,
        "datasource": lambda: mutate_tf_idf(split_buggy(data_generator())),
        "hyperparameters": {}
    }
]

# torch models
try:
    from .models.torch_recurrent.network import RecurrentClassifier

    from skorch import NeuralNetClassifier
    import torch

    WINDOW_SIZES = [10, 20, 40]
    window_data = {size: list(data_generator(size)) for size in WINDOW_SIZES}

    model_specifications.extend([
        {
            "name": "TorchRecurrent",
            "class": Pipeline,
            "datasource": lambda: [
                list(range(len(window_data[WINDOW_SIZES[0]]))),
                list(map(lambda x: 1 if x['label'] == 'CORRECT' else 0, window_data[WINDOW_SIZES[0]]))
            ],
            "args": [[
                ('deindexer', IndexToData(window_data)),
                ('sampler', RandomOverSampler()),
                ('pad2d', PadTransformer(token=0, dtype=np.long)),
                ('model', NeuralNetClassifier(
                    module=RecurrentClassifier,
                    criterion=torch.nn.CrossEntropyLoss,
                    optimizer=torch.optim.SGD,
                    batch_size=10,
                    max_epochs=20,
                    module__output_size=2,
                    module__vocabulary_size=len(get_vocabulary())))
            ]],
            "hyperparameters": {
                "deindexer__window": WINDOW_SIZES,  # maximum window size to collect bytecodes
                'deindexer__function': [lambda x: x['buggy'], lambda x: x['fixed']],

                # [step name]__[skorch object]__[hyperparam axis]
                "model__module__layer_type": [torch.nn.LSTM, torch.nn.RNN],
                "model__module__input_size": [25],
                "model__module__recurrent_hidden_dim": [5],  # dimensionality of the hidden LSTM layers
                "model__module__recurrent_layers": [1, 4],  # number of LSTM layers
                "model__optimizer__lr": [0.1, 0.5],
            }
        }
    ])

except ImportError as err:
    print('PyTorch models were not loaded because of a missing dependency:', err)


# try:
#     from seqlearn.hmm import MultinomialHMM
#
#     model_specifications.extend([
#         {
#             "name": "Multinomial Hidden Markov Model",
#             "class": MultinomialHMM,
#             "datasource": lambda: mutate_tf_idf(mutate_lengths(split_buggy(data_generator()))),
#             "hyperparameters": {}
#         }
#     ])
# except ImportError:
#     print('Multinomial hidden markov model is not loaded because seqlearn is not installed.')
