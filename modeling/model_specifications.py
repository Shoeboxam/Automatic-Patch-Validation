from modeling.preprocess import data_tf_idf, data_counts, get_class_priors, data_buggy

from sklearn.naive_bayes import MultinomialNB
from sklearn.tree import DecisionTreeClassifier

model_specifications = [
    {
        "name": "Multinomial Naive Bayes TF IDF",
        "class": MultinomialNB,
        "datasource": data_tf_idf,
        "hyperparameters": {
            "class_prior": [[.3, .7]]  # [[*reversed(get_class_priors())]]
        }
    },
    {
        "name": "Multinomial Naive Bayes Counts",
        "class": MultinomialNB,
        "datasource": data_counts,
        "hyperparameters": {
            "class_prior": [[*reversed(get_class_priors())]]
        }
    },
    {
        "name": "Decision Tree",
        "class": DecisionTreeClassifier,
        "datasource": data_counts,
        "hyperparameters": {}
    }
]

# torch models
try:
    from models.torch_simple.network import SimpleClassifier
    from models.torch_ann.network import ANNClassifier
    from models.torch_lstm.network import LSTMClassifier

    from skorch import NeuralNetClassifier
    import torch

    model_specifications.extend([
        {
            "name": "TorchLSTM",
            "class": NeuralNetClassifier,
            "datasource": data_buggy,
            "kwargs": {
                "module": LSTMClassifier,
                "criterion": torch.nn.CrossEntropyLoss,
                "optimizer": torch.optim.SGD,
                "batch_size": 1,
                "max_epochs": 20
            },
            "hyperparameters": {
                "module__input_size": [25],
                "module__output_size": [4],

                "module__lstm_hidden_dim": [5],  # dimensionality of the hidden LSTM layers
                "module__lstm_layers": [1, 4],  # number of LSTM layers
                "module__batch_size": [1],

                "optimizer__lr": [0.1, 0.5],
            }
        }
    ])

except ImportError:
    print('PyTorch models were not loaded because PyTorch is not installed.')


# try:
#     from seqlearn.hmm import MultinomialHMM
#
#     model_specifications.extend([
#         {
#             "name": "Multinomial Hidden Markov Model",
#             "class": MultinomialHMM,
#             "datasource": data_tf_idf,
#             "hyperparameters": {}
#         }
#     ])
# except ImportError:
#     print('Multinomial hidden markov model is not loaded because seqlearn is not installed.')