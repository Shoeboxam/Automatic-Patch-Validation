import numpy as np
import json
from collections import Counter
from sklearn.feature_extraction.text import TfidfVectorizer, CountVectorizer

dataset_path = '../dataset.json'


def get_vocabulary():
    vocabulary = set()
    with open(dataset_path, 'r') as infile:
        for obs in json.load(infile):
            vocabulary = vocabulary.union(obs['buggy'])
    return vocabulary


def get_class_priors():
    counts = Counter(datum['label'] for datum in data_generator())
    return [counts[count] / sum(counts.values()) for count in counts]


# TODO: generate data with specified window
def data_generator(window=20):
    with open(dataset_path, 'r') as infile:
        for obs in json.load(infile):
            yield obs


def split_buggy(data):
    print('buggy')
    return list(zip(*((datum['buggy'], datum['label']) for datum in data)))


def split_fixed(data):
    return list(zip(*((datum['buggy'], datum['label']) for datum in data)))


def split_concat(data):
    return list(zip(*(((*datum['buggy'], *datum['fixed']), datum['label']) for datum in data)))


def mutate_lengths(splits):
    return (*splits, list(map(len, splits[0])))


def mutate_tf_idf(splits):
    # data is already tokenized and preprocessed, override with no-ops
    return (TfidfVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x).fit_transform(splits[0]), *splits[1:])


def mutate_counts(splits):
    # data is already tokenized and preprocessed, override with no-ops
    return (CountVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x).fit_transform(splits[0]), *splits[1:])


def mutate_pad(splits):
    longest: int = max(map(len, splits[0]))
    return ([obs + [0] * (longest - len(obs)) for obs in splits[0]], *splits[1:])


def mutate_numpy(splits):
    return [np.array(split) for split in splits]
    # return (np.array(splits[0]), *splits[1:])


def mutate_labels(splits):

    return (splits[0], [1 if obs == 'CORRECT' else 0 for obs in splits[1]], *splits[2:])


if __name__ == '__main__':
    print(*get_class_priors())

