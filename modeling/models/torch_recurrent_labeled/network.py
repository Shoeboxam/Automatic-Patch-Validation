import torch
import torch.nn.functional as F


class RecurrentClassifier(torch.nn.Module):
    def __init__(self, input_size,
                 vocabulary_size, operators_size,
                 layer_type=torch.nn.RNN,
                 recurrent_hidden_dim=20, recurrent_layers=1):
        """Network initialization

        Args:
            input_size: number of features in an observation
            vocabulary_size: number of levels in the input vector
            operators_size: number of levels in operators vector
            layer_type: RNN, LSTM, or GRU
            recurrent_hidden_dim: number of features in each recurrent hidden layer
            recurrent_layers: number of recurrent layers
        """

        super().__init__()

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

    def forward(self, data):
        """Given an observation, return the network prediction

        Args:
            data: a matrix of dimension [batch_size, input_features]
                batch_size: number of observations at each time step
                input_features: number of features at each observation
        Return:
            torch.tensor: the output of the network
        """
        # [None] adds an axis for sequence length: number of time steps represented in the tensor, which is one
        # after making an observation, save the output and update the internal state
        sequences = F.relu(self.embedding(data['sequence']))

        # start all recurrent states with learned initial values
        # the final state after forward prop is not shared between sequences
        batch_size = sequences.shape[0]
        initial_state = (
            self.recurrent_state.expand(-1, batch_size, -1),
            self.cell_state.expand(-1, batch_size, -1)
        )

        # (forward, (hidden state, cell state))[1][0][:, 0] propagates internal hidden state
        # (forward, state)[0][:, -1] propagates final forward pass in sequence
        # take the final state of each sequence
        recurrent_out = F.relu(self.recurrent(sequences, initial_state)[0][:, -1])

        # combine the recurrent output with the operator
        combined = torch.cat((recurrent_out, data['operator'][:, 0]), 1)

        x = self.linear(combined)

        return x if self.training else self.activation_final(x)

