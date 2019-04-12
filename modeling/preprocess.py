import json


dataset_path = '../dataset.json'


def vocab_size():
    vocabulary = set()

    with open(dataset_path, 'r') as infile:
        for obs in json.load(infile):
            vocabulary |= obs


def dataset_sampler():
    with open(dataset_path, 'r') as infile:
        for obs in json.load(infile):
            yield obs


if __name__ == '__main__':
    print(vocab_size())
