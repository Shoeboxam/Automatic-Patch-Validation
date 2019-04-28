import torch
import numpy as np


def train_network(data, model, trainparameters=None):
    torch.manual_seed(0)

    trainparameters = {
        **{
            'epochs': 10,
            'learning_rate': 0.1,
            'device': 'cuda' if torch.cuda.is_available() else 'cpu'
        },
        **(trainparameters or {})
    }

    def get_balanced_weights(data):
        uniques, counts = np.unique([datum[1] for datum in data], return_counts=True)
        labels = dict(zip(uniques, len(data) / counts / len(uniques)))
        weights = np.array([labels[datum[1].item()] for datum in data])
        return weights / weights.sum()

    weights = torch.DoubleTensor(get_balanced_weights(data))
    sampler = torch.utils.data.sampler.WeightedRandomSampler(weights, len(data))

    loader = torch.utils.data.DataLoader(data, batch_size=1, sampler=sampler, num_workers=1)

    get_balanced_weights(loader)
    model.train()
    model.to(trainparameters['device'])

    # when training, sigmoid applied inside BCELossWithLogits for stability
    criterion = torch.nn.CrossEntropyLoss()
    optimizer = torch.optim.SGD(model.parameters(), lr=trainparameters['learning_rate'])

    for epoch in range(trainparameters['epochs']):
        print('Epoch:', epoch)
        for i, (stimulus, expected) in enumerate(loader):

            stimulus = {key: stimulus[key].to(trainparameters['device']) for key in stimulus.keys()}
            expected = expected.to(trainparameters['device'])

            optimizer.zero_grad()

            predicted = model(stimulus)

            loss = criterion(predicted, expected)

            # all function calls since zero_grad() were recorded. Compute gradients from call history and update weights
            loss.backward()
            optimizer.step()

    torch.save(model.state_dict(), './models/torch_recurrent_labeled/weights')


def test_network(data, model):

    model.load_state_dict(torch.load('./models/torch_recurrent_labeled/weights'))
    model.eval()  # turn on evaluation mode

    expected, fitted, probabilities = [], [], []
    loader = torch.utils.data.DataLoader(data, batch_size=1, shuffle=False)

    with torch.no_grad():
        for x, y in loader:
            fitted_batch = model(x)
            probabilities.extend(fitted_batch.tolist())
            fitted.extend(torch.argmax(fitted_batch, dim=1).tolist())
            expected.extend(y.tolist())

    return expected, fitted, probabilities
