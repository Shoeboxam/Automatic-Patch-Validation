import matplotlib.pyplot as plt

import pandas as pd



scores = pd.read_csv('./scores.csv')

scores.plot.barh(x='Algorithm', y=' Accuracy')
plt.show()
# plt.savefig('./score_accuracy.png')
scores.plot.barh(x='Algorithm', y=' Avg F1')
plt.show()
# plt.savefig('./score_f1.png')
scores.plot.barh(x='Algorithm', y=' Avg Precision')
plt.show()
# plt.savefig('./score_precision.png')
scores.plot.barh(x='Algorithm', y=' Avg Recall')
plt.show()
# plt.savefig('./score_recall.png')