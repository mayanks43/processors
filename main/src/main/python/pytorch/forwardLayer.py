import torch
import torch.nn

from finalLayer import FinalLayer
from greedyForwardLayer import GreedyForwardLayer
from viterbiForwardLayer import ViterbiForwardLayer

from utils import *

def ForwardLayer(FinalLayer):
    def __init__(self, inputSize, isDual, t2i, i2t, actualInputSize, nonlinearity, dropoutProb, spans = None):
        self.inputSize = inputSize
        self.isDual = isDual
        self.t2i = t2i
        self.i2t = i2t
        self.spans = spans
        self.nonlinearity = nonlinearity

        self.pH = nn.Linear(actualInputSize, len(t2i))
        self.pRoot = torch.rand(inputSize) #TODO: Not sure about the shape here
        self.dropoutProb = dropoutProb

        self.inDim = spanLength(spans) if spans is not None else inputSize
        self.outDim = len(t2i)


    def pickSpan(self, v):
        if self.spans is None:
            return v
        else:
            # Zheng: Will spans overlap?
            vs = list()
            for span in self.spans:
                e = torch.index_select(v, 0, torch.tensor([span[0], span[1]]))
                vs.append(e)
            return torch.cat(vs)

    def forward(inputExpressions, doDropout, headPositionsOpt = None):
        emissionScores = list()
        if not self.isDual:
            # Zheng: Why the for loop here? Can we just use matrix manipulation?
            for i, e in enumerate(inputExpressions):
                argExp = expressionDropout(self.pickSpan(e), self.dropoutProb, doDropout)
                l1 = expressionDropout(self.pH(argExp), self.dropoutProb, doDropout)
                if nonlinearity == NONLIN_TANH:
                    l1 = torch.tanh(l1)
                elif nonlinearity == NONLIN_RELU:
                    l1 = torch.relu(l1)
                emissionScores.append(l1)
        else:
            if headPositionsOpt is None:
                raise RuntimeError("ERROR: dual task without information about head positions!")
            for i, e in enumerate(inputExpressions):
                headPosition = headPositionsOpt[i]
                argExp = expressionDropout(pickSpan(e), self.dropoutProb, doDropout)
                if headPosition >= 0:
                    # there is an explicit head in the sentence
                    predExp = expressionDropout(pickSpan(inputExpressions[headPosition]), self.dropout, doDropout)
                else:
                    # the head is root. we used a dedicated Parameter for root
                    # Zheng: Why not add root node to the input sequence at the beginning?
                    predExp = expressionDropout(pickSpan(self.pRoot), self.dropout, doDropout)
                ss = torch.cat([argExp, predExp])
                l1 = expressionDropout(self.pH(ss), self.dropoutProb, doDropout)
                if nonlinearity == NONLIN_TANH:
                    l1 = torch.tanh(l1)
                elif nonlinearity == NONLIN_RELU:
                    l1 = torch.relu(l1)
                emissionScores.append(l1)
        return torch.stack(emissionScores)

    @staticmethod
    def load(x2i):
        inferenceType = x2i["inferenceType"]
        if inferenceType == TYPE_VITERBI:
            pass
            # TODO
            # return ViterbiForwardLayer.load(x2i)
        elif inferenceType == TYPE_GREEDY:
            return GreedyForwardLayer.load(x2i)
        else:
            raise RuntimeError(f"ERROR: unknown forward layer type {inferenceType}!")

    @staticmethod
    def initialize(config, paramPrefix, labelCounter, isDual, inputSize):
        if(not config.__contains__(paramPrefix)):
            return None

        inferenceType = config.get_string(paramPrefix + ".inference", "greedy")
        dropoutProb = config.get_float(paramPrefix + ".dropoutProb", DEFAULT_DROPOUT_PROBABILITY)

        nonlinAsString = config.get_string(paramPrefix + ".nonlinearity", "")
        if nonlinAsString in nonlin_map:
            nonlin = nonlin_map[nonlinAsString]
        else:
            raise RuntimeError(f"ERROR: unknown non-linearity {nonlinAsString}!")

        t2i = {t:i for i, t in enumerate(labelCounter.keys())}
        i2t = {i:t for t, i in t2i.items()}

        spanConfig = config.get_string(paramPrefix + ".span", "")
        if spanConfig is "":
            span = None
        else:
            span = parseSpan(spanConfig)

        if span:
            l = spanLength(span)
            actualInputSize = 2*l if isDual else l
        else:
            actualInputSize = 2*inputSize if isDual else inputSize

        if inferenceType == TYPE_GREEDY_STRING:
            return GreedyForwardLayer(inputSize, isDual, t2i, i2t, actualInputSize, span, nonlin, dropoutProb)
        elif inferenceType == TYPE_VITERBI_STRING:
            pass
            # TODO
            # layer = ViterbiForwardLayer(inputSize, isDual, t2i, i2t, actualInputSize, span, nonlin, dropoutProb)
            # layer.initializeTransitions()
            # return layer
        else:
            raise RuntimeError(f"ERROR: unknown inference type {inferenceType}!")
    
def spanLength(spans):
    s = 0
    for x in spans:
        s += x[1] - x[0]
    return s

def parseSpan(spanParam, inputSize):
    spans = list()
    spanParamTokens = spanParam.split(",")
    for spanParamToken in spanParamTokens:
        spanTokens = spanParamToken.split('-')
        assert(len(spanTokens) == 2)
        spans.append((int(spanTokens[0]), int(spanTokens[1])))
    return spans

def spanToString(spans):
    s = ""
    first = True
    for span in spans:
        if not first:
            s += ","
        s += f"{span[0]}-{span[1]}"
        first = False
    return s


























