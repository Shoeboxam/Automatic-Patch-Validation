import json
from collections import Counter
from sklearn.feature_extraction.text import TfidfVectorizer, CountVectorizer

dataset_path = '../dataset.json'


def get_vocabulary():
    vocabulary = set()

    with open(dataset_path, 'r') as infile:
        for obs in json.load(infile):
            vocabulary = vocabulary.union(obs['buggy'])
    print(vocabulary)


def get_class_priors():
    counts = Counter(datum['label'] for datum in data_generator())
    return [counts[count] / sum(counts.values()) for count in counts]


def data_generator():
    with open(dataset_path, 'r') as infile:
        for obs in json.load(infile):
            yield obs


def data_buggy():
    return zip(*((datum['buggy'], datum['label']) for datum in data_generator()))


def data_fixed():
    return zip(*((datum['buggy'], datum['label']) for datum in data_generator()))


def data_concat():
    return zip(*(((*datum['buggy'], *datum['fixed']), datum['label']) for datum in data_generator()))


def data_tf_idf():
    X, Y = zip(*(([*datum['buggy'], *datum['buggy']], datum['label']) for datum in data_generator()))
    # data is already tokenized and preprocessed, override with no-ops
    X_tfIdf = TfidfVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x).fit_transform(X)
    return X_tfIdf, Y


def data_counts():
    X, Y = zip(*(([*datum['buggy'], *datum['buggy']], datum['label']) for datum in data_generator()))
    # data is already tokenized and preprocessed, override with no-ops
    X_counts = CountVectorizer(preprocessor=lambda x: x, tokenizer=lambda x: x).fit_transform(X)
    return X_counts, Y


if __name__ == '__main__':
    print(*get_class_priors())

