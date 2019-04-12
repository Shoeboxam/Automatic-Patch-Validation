from modeling.models.torch_lstm.network import LSTMClassifier

import numpy as np
import torch


def train_lstm(data, hyperparameters, trainparameters=None):
    torch.manual_seed(0)

    trainparameters = {
        **{'epochs': 10, 'learning_rate': 0.1},
        **(trainparameters or {})
    }

    model = LSTMClassifier(**hyperparameters)

    criterion = torch.nn.CrossEntropyLoss()
    optimizer = torch.optim.SGD(model.parameters(), lr=trainparameters['learning_rate'])

    for epoch in range(trainparameters['epochs']):
        for i, datum in enumerate(data):
            print(f'Step: {i}')
            model.zero_grad()

            predicted = model(datum['buggy'])
            loss = criterion(predicted, datum['label'])

            # all function calls since zero_grad() were recorded. Compute gradients from call history and update weights
            loss.backward()
            optimizer.step()

    torch.save(model.state_dict(), './modeling/models/torch_lstm/weights')


def test_lstm(data, networkparameters):
    model = LSTMClassifier(**networkparameters)

    model.load_state_dict(torch.load('./modeling/models/torch_lstm/weights'))
    model.eval()  # turn on evaluation mode

    expected, actual = [], []

    with torch.no_grad():

        for datum in data:
            actual.append(int(np.argmax(np.squeeze(model(datum['buggy'])))))
            expected.append(int(datum['label']))

    return expected, actual
