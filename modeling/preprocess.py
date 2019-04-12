import json


dataset_path = '../dataset.json'


def get_vocabulary():
    vocabulary = set()

    with open(dataset_path, 'r') as infile:
        for obs in json.load(infile):
            vocabulary = vocabulary.union(obs['buggy'])
    print(vocabulary)


def dataset_sampler():
    with open(dataset_path, 'r') as infile:
        for obs in json.load(infile):
            yield obs


if __name__ == '__main__':
    print(get_vocabulary())

