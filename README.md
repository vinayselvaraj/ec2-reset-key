# ec2-reset-key : Reset EC2 SSH keys/passwords

This tool can be used to reset lost SSH keys on EC2 Linux instances and to enable Guest access on EC2 Windows instances when the Administrator password is lost.  

## Requirements

You will need to have the following in order to use ec2-reset-key:

* Java 1.6+
* AWS Access Key and Secret Access Key or IAM Role

## Usage

```
usage: ec2-reset-key -i <instance id> -k <keypair name>
 -f,--publicKey <arg>     Linux Only: Path to public key file (i.e.
                          ~/mypublic_key.pub)
 -h,--help                help
 -i,--instance-id <arg>   Instance ID of the EC2 instance (e.g. i-XXXXXX)
 -r,--region <arg>        region (us-east-1, us-west-1, etc...)
 -t,--type <arg>          Type of instance (i.e. linux, windows)
```

## EC2 Linux Example

     bin/ec2-reset-key -r us-east-1 -t linux -f ~/.secrets/ssh_public_key -i i-a71b3b49


## Windows EC2 Example

     bin/ec2-reset-key -r us-east-1 -t windows -i i-9bdfd070


