import torch
from sklearn.preprocessing import OneHotEncoder
import random

from torch.utils import data


class DatasetLabeledBytecode(data.Dataset):
    def __init__(self, data, split=None, seed=1, test_size=5):
        self.operatorEncoder = OneHotEncoder()
        self.operatorEncoder.fit([[datum['operator']] for datum in data])

        self.data = data

        if split:
            indices = {'CORRECT': [], 'INCORRECT': []}
            for i, datum in enumerate(data):
                indices[datum['label']].append(i)
            samples = [index
                       for label in indices
                       for index in random.Random(seed).sample(indices[label], test_size)]
            if split == 'test':
                self.data = [self.data[index] for index in samples]
            if split == 'train':
                samples = set(samples)
                self.data = [self.data[index] for index in range(len(self.data)) if index not in samples]

    def __len__(self):
        return len(self.data)

    def __getitem__(self, index):
        return {
            'sequence': torch.Tensor(self.data[index]['buggy']).long(),
            'operator': torch.Tensor(self.operatorEncoder.transform([[self.data[index]['operator']]]).todense())
        }, torch.tensor(1 if self.data[index]['label'] == 'CORRECT' else 0).long()
