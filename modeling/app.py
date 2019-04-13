from sklearn.model_selection import StratifiedKFold

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


def evaluate(expected, actual, probas, name=''):
    """Collect diagnostics about model performance

    Args:
        expected: array of expected values
        actual: array of actual values
    """

    if print_results:
        print("\nClassification report:")
        print(classification_report(expected, actual))

        print("\nConfusion matrix:")
        print(confusion_matrix(expected, actual))

        print("\nAccuracy Score:")
        print(accuracy_score(expected, actual))

        if probas is not None:
            print("\n<Receiver Operating Characteristic Plot>")
            plot_roc(expected, probas)
            plt.title('ROC Curve ' + name)
            plt.savefig(f'./plots/{name}.png')
            # plt.show()

    scores = [
        precision_score(expected, actual, average='macro'),
        recall_score(expected, actual, average='macro'),
        f1_score(expected, actual, average='macro'),
        accuracy_score(expected, actual),
    ]
    scores = [round(score, 4) for score in scores]

    with open('./scores.csv', 'a') as score_file:
        score_file.writelines(', '.join([str(j) for j in [name, *scores]]) + '\n')


def run_lstm():
    from modeling.models.torch_lstm.train import train_lstm, test_lstm
    sampler = lambda: sampler()

    params = {
        'input_size': 25,
        'output_size': 4,

        'lstm_hidden_dim': 20,
        'lstm_layers': 2,
        'batch_size': 1
    }
    train_lstm(sampler, params)
    evaluate(*test_lstm(sampler, params))


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

        # if model_spec['name'] != 'Multinomial Naive Bayes TF IDF':
        #     continue

        X_train, X_test, y_train, y_test = train_test_split(*model_spec['datasource']())

        # catch warnings in bulk, show frequencies for each after grid search
        with warnings.catch_warnings(record=True) as warns:

            print(f'{model_spec["name"]}: Tuning hyper-parameters')

            # create an instance of the model
            model = model_spec['class'](**(model_spec.get('kwargs', {})))

            search = GridSearchCV(model, param_grid=model_spec['hyperparameters'], cv=StratifiedKFold(n_splits=5), scoring='accuracy')
            search.fit(X_train, y_train)

            y_true, y_pred = y_test, search.predict(X_test)

            try:
                y_probas = search.predict_proba(X_test)
            except AttributeError:
                y_probas = None

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

            evaluate(y_true, y_pred, y_probas, name=model_spec['name'])

            formatted_params = [
                model_spec['name'],
                *[f'{key}={value}' for key, value in best_params.items()]
            ]

            with open('./parameters.csv', 'a') as params_file:
                params_file.writelines(', '.join(formatted_params) + '\n')
