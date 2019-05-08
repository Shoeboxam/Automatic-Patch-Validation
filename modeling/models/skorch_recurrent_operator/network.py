import torch
import torch.nn.functional as F
from sklearn.preprocessing import OneHotEncoder


class RecurrentClassifierOperator(torch.nn.Module):
    """
    Recurrent network, with feature union in the fully connected layer
    The bytecode sequence is embedded, and then passed through a recurrent net
    The operator is encoded and feature unioned with the final recurrent forward pass

    Copy of torch_recurrent_operator, with an embedded dataset for skorch/sklearn compatibility
    """
    def __init__(self, data, window_size, input_size,
                 vocabulary_size, operators_size,
                 layer_type=torch.nn.RNN,
                 recurrent_hidden_dim=20, recurrent_layers=1):
        """Network initialization

        Args:
            data: copy of the entire dataset at a hyperparameter set of window sizes
            input_size: number of features in an observation between the embedding and recurrent layer
            vocabulary_size: number of levels in the input vector
            operators_size: number of levels in operators vector
            layer_type: RNN, LSTM, or GRU
            recurrent_hidden_dim: number of features in each recurrent hidden layer
            recurrent_layers: number of recurrent layers
        """

        super().__init__()

        self.data = data
        self.window_size = window_size

        self.oneHotEncoder = OneHotEncoder(sparse=False)
        self.oneHotEncoder.fit([[datum['operator']] for datum in data[window_size]])

        self.embedding = torch.nn.Embedding(vocabulary_size * 2, input_size)

        # outputs an intermediate feature vector
        self.recurrent = layer_type(
            input_size=input_size,
            hidden_size=recurrent_hidden_dim,
            num_layers=recurrent_layers,
            batch_first=True)

        self.recurrent_state = torch.nn.Parameter(
            torch.zeros(recurrent_layers, 1, recurrent_hidden_dim))
        self.cell_state = torch.nn.Parameter(
            torch.zeros(recurrent_layers, 1, recurrent_hidden_dim))

        # linear layer maps from intermediate feature space to class label
        self.linear = torch.nn.Linear(recurrent_hidden_dim + operators_size, 2)

        # activation transforms features to probability vector
        self.activation_final = torch.nn.Sigmoid()

    def forward(self, indices):
        """Given an observation, return the network prediction

        Args:
            indices: a matrix of dimension [batch_size, 1]
                batch_size: number of observations in the batch
                input_features: always one, the index
        Return:
            torch.tensor: the output of the network
        """

        data = [self.data[self.window_size][idx[0]] for idx in indices]

        # since every sequence has its own length, each sequence gets its own gradient tape
        # tapes will be stacked later on, once lengths are standardized
        batch_size = 1

        # after making an observation, save the output and update the internal state
        sequences = [
            F.relu(self.embedding(torch.tensor([datum['buggy']])))
            for datum in data]

        # start all recurrent states with learned initial values
        # the final state after forward prop is not shared between sequences
        initial_state = (
            self.recurrent_state.expand(-1, batch_size, -1),
            self.cell_state.expand(-1, batch_size, -1)
        )

        # ~ UNUSED: (forward, (hidden state, cell state))[1][0][:, 0] propagates internal hidden state
        # ~ USED  : (forward, state)[0][:, -1] propagates final forward pass in sequence
        # take the final state of each sequence
        # the final [0] removes the leading singleton batch index, to prepare for stacking
        recurrent_out = [
            F.relu(self.recurrent(sequence, initial_state)[0][:, -1])[0]
            for sequence in sequences]

        # stack with leading batch index
        recurrent_out = torch.stack(recurrent_out)

        operator = torch.FloatTensor(
            self.oneHotEncoder.transform([[datum['operator']] for datum in data]))

        # combine the batch-stacked recurrent output with the batch-stacked operator
        combined = torch.cat((recurrent_out, operator), 1)

        x = self.linear(combined)

        # only apply the Sigmoid if not training, to take advantage of the log-sum-exp performance/stability trick
        return x if self.training else self.activation_final(x)

