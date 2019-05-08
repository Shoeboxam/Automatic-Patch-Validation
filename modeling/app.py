from sklearn.model_selection import KFold

from scikitplot.metrics import plot_roc
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.metrics import precision_score, recall_score, f1_score, accuracy_score

from matplotlib import pyplot as plt

import warnings
from collections import Counter

import json

from modeling.model_specifications import model_specifications
from sklearn.model_selection import GridSearchCV, train_test_split
import os
import shutil


print_results = True


def evaluate(expected, fitted, probabilities, name=''):
    """Collect diagnostics about model performance

    Args:
        expected: array of expected values
        fitted: array of fitted values
    """

    if print_results:
        print("\nClassification report:")
        print(classification_report(expected, fitted))

        print("\nConfusion matrix:")
        print(confusion_matrix(expected, fitted))

        print("\nAccuracy Score:")
        print(accuracy_score(expected, fitted))

        if probabilities is not None:
            print("\n<Receiver Operating Characteristic Plot>")
            plot_roc(expected, probabilities)
            plt.title('ROC Curve ' + name)
            plt.savefig(f'./plots/{name}.png')
            # plt.show()

    scores = [
        precision_score(expected, fitted, average='macro'),
        recall_score(expected, fitted, average='macro'),
        f1_score(expected, fitted, average='macro'),
        accuracy_score(expected, fitted),
    ]
    scores = [round(score, 4) for score in scores]

    with open('./scores.csv', 'a') as score_file:
        score_file.writelines(', '.join([str(j) for j in [name, *scores]]) + '\n')


def manual_torch_recurrent_operator():

    from modeling.models.torch_recurrent_operator.data import DatasetLabeledBytecode
    from modeling.models.torch_recurrent_operator.network import RecurrentClassifierOperator
    from modeling.models.torch_recurrent_operator.train import train_network, test_network
    from modeling.preprocess import data_generator, get_operations, get_vocabulary
    import torch

    data_preferences = {"labeled": True, "window": 21}

    hyperparameters = {
        "input_size": 10,
        "window_size": 21,
        "vocabulary_size": len(get_vocabulary(**data_preferences)),
        "operators_size": len(get_operations(**data_preferences)),
        "layer_type": torch.nn.LSTM,
        "recurrent_hidden_dim": 5,
        "recurrent_layers": 1,
    }

    trainparameters = {
        'epochs': 20
    }

    train_network(
        DatasetLabeledBytecode(data_generator(**data_preferences), split='train', seed=2),
        RecurrentClassifierOperator(**hyperparameters),
        trainparameters
    )

    evaluate(*test_network(
        DatasetLabeledBytecode(data_generator(**data_preferences), split='test', seed=2),
        RecurrentClassifierOperator(**hyperparameters)
    ), name='Recurrent_Labeled')


if __name__ == '__main__':

    with open('./scores.csv', 'w') as file:
        file.write(', '.join([
            'Algorithm',
            'Avg Precision',
            'Avg Recall',
            'Avg F1',
            'Accuracy'
        ]) + '\n')
    if os.path.exists('./parameters.csv'):
        os.remove('./parameters.csv')

    if os.path.exists('./plots/'):
        shutil.rmtree('./plots/')
    os.mkdir('./plots/')

    for model_spec in model_specifications:

        # if model_spec['name'] != 'Multinomial Hidden Markov Model':
        #     continue

        splits = train_test_split(*model_spec['datasource']())

        train, test = splits[::2], splits[1::2]

        # catch warnings in bulk, show frequencies for each after grid search
        with warnings.catch_warnings(record=True) as warns:

            print(f'{model_spec["name"]}: Tuning hyper-parameters')

            # create an instance of the model
            model = model_spec['class'](
                *(model_spec.get('args', [])),
                **(model_spec.get('kwargs', {})))

            search = GridSearchCV(model,
                                  param_grid=model_spec['hyperparameters'],
                                  cv=KFold(n_splits=10),
                                  scoring='accuracy')
            search.fit(*train)

            y_true, y_pred = test[1], search.predict(test[0])

            try:
                y_prob = search.predict_proba(test[0])
            except AttributeError:
                y_prob = None

            best_params = search.best_params_

            if print_results:
                print("Grid scores on development set:")
                means = search.cv_results_['mean_test_score']
                stds = search.cv_results_['std_test_score']
                params = search.cv_results_['params']

                for mean, std, params in zip(means, stds, params):
                    print("%0.3f (+/-%0.03f) for %r" % (mean, std * 2, params))

            warning_counts = dict(Counter([str(warn.category) for warn in warns]))
            if warning_counts:
                print('Warnings during grid search:')
                print(json.dumps(warning_counts, indent=4))

            evaluate(y_true, y_pred, y_prob, name=model_spec['name'])

            formatted_params = [
                model_spec['name'],
                *[f'{key}={value}' for key, value in best_params.items()]
            ]

            with open('./parameters.csv', 'a') as params_file:
                params_file.writelines(', '.join(formatted_params) + '\n')
