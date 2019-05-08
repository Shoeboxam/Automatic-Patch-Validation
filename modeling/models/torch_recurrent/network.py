import torch


class RecurrentClassifier(torch.nn.Module):
    def __init__(self, input_size, output_size, vocabulary_size, layer_type=torch.nn.RNN, recurrent_hidden_dim=20, recurrent_layers=1):
        """Network initialization

        Args:
            input_size: number of features in an observation
            output_size: number of classes in a prediction
            vocabulary_size: number of levels in the input vector
            recurrent_hidden_dim: number of features in each recurrent hidden layer
            recurrent_layers: number of layers in the recurrent
        """

        super().__init__()
        # number of output nodes from LSTM, and input nodes to typical linear layer
        self.recurrent_hidden_dim = recurrent_hidden_dim
        self.recurrent_layers = recurrent_layers

        self.embedding = torch.nn.Embedding(vocabulary_size * 2, input_size)

        # outputs an intermediate feature vector
        self.recurrent = layer_type(
            input_size=input_size,
            hidden_size=recurrent_hidden_dim,
            num_layers=recurrent_layers,
            batch_first=True)

        # linear layer maps from intermediate feature space to class label
        self.linear = torch.nn.Linear(recurrent_hidden_dim, output_size)
        # activation transforms features to probability vector
        self.activation_final = torch.nn.LogSoftmax(dim=1)

        self.recurrent_state = ()

    def recurrent_state_init(self, batch_size):
        """sets the state of the recurrent layers"""
        self.recurrent_state = (
            torch.zeros(self.recurrent_layers, batch_size, self.recurrent_hidden_dim),
            torch.zeros(self.recurrent_layers, batch_size, self.recurrent_hidden_dim)
        )

    def forward(self, data):
        print(data)
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
        data = self.embedding(data)

        # reset the state with current batch size
        # the state is not being used, since observations are uncorrelated between, correlated within
        self.recurrent_state_init(data.shape[0])

        recurrent_out, self.recurrent_state = self.recurrent(data, self.recurrent_state)
        # take the final recurrent state of each time series
        return self.activation_final(self.linear(recurrent_out[:, -1]))
