from .network import RecurrentClassifier
from .data import Dataset

import numpy as np
import torch


def train_network(data, hyperparameters, trainparameters=None):
    torch.manual_seed(0)

    trainparameters = {
        **{'epochs': 10, 'learning_rate': 0.1},
        **(trainparameters or {})
    }

    loader = torch.utils.data.DataLoader(Dataset(data), batch_size=1, shuffle=True, num_workers=1)

    model = RecurrentClassifier(**hyperparameters)

    criterion = torch.nn.BCELoss()
    optimizer = torch.optim.SGD(model.parameters(), lr=trainparameters['learning_rate'])

    for epoch in range(trainparameters['epochs']):
        for i, (stimulus, actual) in enumerate(loader):
            # print(f'Step: {i}')
            model.zero_grad()

            predicted = model(stimulus)
            loss = criterion(predicted, actual)

            # all function calls since zero_grad() were recorded. Compute gradients from call history and update weights
            loss.backward()
            optimizer.step()

    torch.save(model.state_dict(), './models/torch_recurrent_labeled/weights')


def test_network(data, networkparameters):
    model = RecurrentClassifier(**networkparameters)

    model.load_state_dict(torch.load('./models/torch_recurrent_labeled/weights'))
    model.eval()  # turn on evaluation mode

    expected, actual = [], []

    with torch.no_grad():

        for datum in data:
            actual.append(int(np.argmax(np.squeeze(model({
                "sequence": datum['buggy'],
                "operation": datum['operator']
            })))))
            expected.append(int(datum['label']))

    return expected, actual
