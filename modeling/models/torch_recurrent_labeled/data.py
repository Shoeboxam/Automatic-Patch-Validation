import torch
from sklearn.preprocessing import OneHotEncoder


class Dataset(torch.utils.data.Dataset):
    def __init__(self, data):
        self.operatorEncoder = OneHotEncoder()
        self.operatorEncoder.fit([[datum['operator']] for datum in data])

        self.data = data

    def __len__(self):
        return len(self.data)

    def __getitem__(self, index):
        return {
            'sequence': torch.Tensor(self.data[index]['buggy']).long(),
            'operator': torch.Tensor(self.operatorEncoder.transform([[self.data[index]['operator']]]).todense())
        }, torch.Tensor([1, 0] if self.data[index]['label'] == 'CORRECT' else [1, 0])
