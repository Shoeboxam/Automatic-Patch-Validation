import numpy as np
import json
from collections import Counter
import os
import subprocess
import re
from sklearn.feature_extraction.text import TfidfVectorizer, CountVectorizer


def get_vocabulary(labeled=False, window=21):
    vocabulary = set()
    for obs in data_generator(labeled, window):
        vocabulary = vocabulary.union(obs['buggy'])
    return vocabulary


def get_operations(labeled=False, window=21):
    operations = set()
    for obs in data_generator(labeled, window):
        operations.add(obs['operator'])
    return operations


def get_class_priors():
    counts = Counter(datum['label'] for datum in data_generator())
    return [counts[count] / sum(counts.values()) for count in counts]


def data_generator(labeled=False, window=21):
    data_path = f'../dataset_{"labeled" if labeled else "unlabeled"}_{window}.txt'

    if not os.path.exists(data_path):
        if labeled:
            print('Writing', data_path)
            ps = subprocess.Popen(['./run-cg', str(window)], stdout=subprocess.PIPE, cwd='../')
            with open(data_path, 'w') as outfile:
                outfile.writelines(
                    re.sub(r'}(?=[^.]*})', '},', ps.communicate()[0].decode('utf-8')).split('\n')
                )

        if not labeled:
            ps = subprocess.Popen(['./run', str(window)], stdout=subprocess.PIPE, cwd='../')
            with open(data_path, 'w') as outfile:
                outfile.writelines(ps.communicate()[0].decode('utf-8'))

    with open(data_path, 'r') as infile:
        return json.load(infile)


def split_labeled(data):
    return list(zip(*((datum['buggy'], datum['operator'], datum['label']) for datum in data)))


def split_buggy(data):
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


def mutate_labels(splits):
    return (splits[0], [1 if obs == 'CORRECT' else 0 for obs in splits[1]], *splits[2:])


if __name__ == '__main__':
    print(*get_class_priors())

