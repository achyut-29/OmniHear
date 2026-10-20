import tensorflow as tf
import tensorflow_hub as hub
import numpy as np

print('Loading YAMNet...')
yamnet = hub.load('https://tfhub.dev/google/yamnet/1')

print('Loading Weights...')
w = np.load('weights.npz')
w1 = w['w1']
b1 = w['b1']
w2 = w['w2']
b2 = w['b2']

class OmniHearModel(tf.Module):
    def __init__(self, yamnet_model, w1, b1, w2, b2):
        super().__init__()
        self.yamnet = yamnet_model
        # Use constants instead of variables so they are completely frozen
        self.dense1_w = tf.constant(w1, dtype=tf.float32)
        self.dense1_b = tf.constant(b1, dtype=tf.float32)
        self.dense2_w = tf.constant(w2, dtype=tf.float32)
        self.dense2_b = tf.constant(b2, dtype=tf.float32)

    @tf.function(input_signature=[tf.TensorSpec(shape=[15600], dtype=tf.float32, name='waveform')])
    def __call__(self, waveform):
        # YAMNet returns (scores, embeddings, spectrogram)
        # We need the embeddings (shape [N, 1024])
        _, emb, _ = self.yamnet(waveform)
        
        # Dense 1
        x = tf.matmul(emb, self.dense1_w) + self.dense1_b
        x = tf.nn.relu(x)
        
        # Dense 2
        out = tf.matmul(x, self.dense2_w) + self.dense2_b
        
        # We only want the first frame since the audio is just 0.975s
        # which yields 1 embedding frame
        return out

model = OmniHearModel(yamnet, w1, b1, w2, b2)
concrete_func = model.__call__.get_concrete_function()

print('Converting to TFLite...')
converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete_func])
# Ensure variables are frozen
converter.experimental_enable_resource_variables = False 
tflite_model = converter.convert()

output_path = 'app/src/main/assets/custom_safehear.tflite'
with open(output_path, 'wb') as f:
    f.write(tflite_model)

print('EXPORT SUCCESSFUL!')
