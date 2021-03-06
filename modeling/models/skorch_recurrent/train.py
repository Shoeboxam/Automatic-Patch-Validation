from modeling.models.skorch_recurrent.network import RecurrentClassifier

import numpy as np
import torch


def train_network(data, hyperparameters, trainparameters=None):
    torch.manual_seed(0)

    trainparameters = {
        **{'epochs': 10, 'learning_rate': 0.1},
        **(trainparameters or {})
    }

    model = RecurrentClassifier(**hyperparameters)

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

    torch.save(model.state_dict(), './modeling/models/torch_recurrent/weights')


def test_network(data, networkparameters):
    model = RecurrentClassifier(**networkparameters)

    model.load_state_dict(torch.load('./modeling/models/torch_recurrent/weights'))
    model.eval()  # turn on evaluation mode

    expected, actual = [], []

    with torch.no_grad():

        for datum in data:
            actual.append(int(np.argmax(np.squeeze(model(datum['buggy'])))))
            expected.append(int(datum['label']))

    return expected, actual
